// ========================================================================
// Copyright (c) 2004-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.ReadInterest;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An AsyncConnection that acts as an interceptor between and EndPoint and another
 * Connection, that implements TLS encryption using an {@link SSLEngine}.
 * <p/>
 * The connector uses an {@link EndPoint} (like {@link SelectChannelEndPoint}) as
 * it's source/sink of encrypted data.   It then provides {@link #getSslEndPoint()} to
 * expose a source/sink of unencrypted data to another connection (eg HttpConnection).
 */
public class SslConnection extends AbstractAsyncConnection
{
    private static final Logger LOG = Log.getLogger(SslConnection.class);
    private final ByteBufferPool _bufferPool;
    private final SSLEngine _sslEngine;
    private final SslEndPoint _appEndPoint;
    private ByteBuffer _appIn;
    private ByteBuffer _netIn;
    private ByteBuffer _netOut;
    private final boolean _netDirect = false;
    private final boolean _appDirect = false;
    private SSLEngineResult _unwrapResult;
    private SSLEngineResult _wrapResult;

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, AsyncEndPoint endPoint, SSLEngine sslEngine)
    {
        super(endPoint, executor, true);
        this._bufferPool = byteBufferPool;
        this._sslEngine = sslEngine;
        this._appEndPoint = new SslEndPoint();
    }

    public SSLEngine getSSLEngine()
    {
        return _sslEngine;
    }

    public AsyncEndPoint getSslEndPoint()
    {
        return _appEndPoint;
    }

    @Override
    public void onOpen()
    {
        try
        {
            super.onOpen();

            // Begin the handshake
            _sslEngine.beginHandshake();

            if (_sslEngine.getUseClientMode())
                _appEndPoint.write(null, new Callback.Empty<>(), BufferUtil.EMPTY_BUFFER);
        }
        catch (SSLException x)
        {
            getEndPoint().close();
            throw new RuntimeIOException(x);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onFillable()
    {
        LOG.debug("{} onReadable", this);

        // wake up whoever is doing the fill or the flush so they can
        // do all the filling, unwrapping ,wrapping and flushing
        if (_appEndPoint._readInterest.isInterested())
            _appEndPoint._readInterest.readable();

        // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
        if (_appEndPoint._writeFlusher.isWritePending() && _appEndPoint._flushUnwrap)
        {
            _appEndPoint._flushUnwrap = false;
            _appEndPoint._writeFlusher.completeWrite();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        super.onFillInterestedFailed(cause);

        if (_appEndPoint._readInterest.isInterested())
            _appEndPoint._readInterest.failed(cause);

        if (_appEndPoint._writeFlusher.isWritePending() && _appEndPoint._flushUnwrap)
        {
            _appEndPoint._flushUnwrap = false;
            _appEndPoint._writeFlusher.failed(cause);
        }

    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("SslConnection@%x{%s,%s%s}",
                hashCode(),
                _sslEngine.getHandshakeStatus(),
                _appEndPoint._readInterest.isInterested() ? "R" : "",
                _appEndPoint._writeFlusher.isWritePending() ? "W" : "");
    }

    /* ------------------------------------------------------------ */
    public class SslEndPoint extends AbstractEndPoint implements AsyncEndPoint
    {
        private AsyncConnection _connection;
        private boolean _fillWrap;
        private boolean _flushUnwrap;
        private boolean _netWriting;
        private boolean _underflown;
        private boolean _ishut = false;

        @Override
        public void onOpen()
        {
        }

        @Override
        public void onClose()
        {
        }

        private final Callback<Void> _writeCallback = new Callback<Void>()
        {

            @Override
            public void completed(Void context)
            {
                synchronized (SslEndPoint.this)
                {
                    LOG.debug("{} write.complete {}", SslConnection.this, _netWriting ? (_fillWrap ? "FW" : "F") : (_fillWrap ? "W" : ""));

                    releaseNetOut();

                    _netWriting = false;
                    if (_fillWrap)
                    {
                        _fillWrap = false;
                        _readInterest.readable();
                    }

                    if (_writeFlusher.isWritePending())
                        _writeFlusher.completeWrite();
                }
            }

            @Override
            public void failed(Void context, Throwable x)
            {
                synchronized (SslEndPoint.this)
                {
                    LOG.debug("{} write.failed", SslConnection.this, x);
                    if (_netOut != null)
                        BufferUtil.clear(_netOut);
                    releaseNetOut();
                    _netWriting = false;
                    if (_fillWrap)
                    {
                        _fillWrap = false;
                        _readInterest.failed(x);
                    }

                    if (_writeFlusher.isWritePending())
                        _writeFlusher.failed(x);

                    // TODO release all buffers??? or may in onClose
                }
            }
        };

        private final ReadInterest _readInterest = new ReadInterest()
        {
            @Override
            protected boolean needsFill() throws IOException
            {
                synchronized (SslEndPoint.this)
                {
                    // Do we already have some app data
                    if (BufferUtil.hasContent(_appIn))
                        return true;

                    // If we are not underflown and have net data
                    if (!_underflown && BufferUtil.hasContent(_netIn))
                        return true;

                    // So we are not read ready

                    // Are we actually write blocked?
                    if (_fillWrap)
                    {
                        // we must be blocked trying to write before we can read
                        // If we have written the net data
                        if (BufferUtil.isEmpty(_netOut))
                        {
                            // pretend we are readable so the wrap is done by next readable callback
                            _fillWrap = false;
                            return true;
                        }

                        // otherwise write the net data
                        _netWriting = true;
                        getEndPoint().write(null, _writeCallback, _netOut);
                    }
                    else
                        // Normal readable callback
                        SslConnection.this.fillInterested();

                    return false;
                }
            }
        };

        private final WriteFlusher _writeFlusher = new WriteFlusher(this)
        {
            @Override
            protected void onIncompleteFlushed()
            {
                synchronized (SslEndPoint.this)
                {
                    // If we have pending output data,
                    if (BufferUtil.hasContent(_netOut))
                    {
                        // write it
                        _netWriting = true;
                        getEndPoint().write(null, _writeCallback, _netOut);
                    }
                    // TODO test this with _flushInwrap
                    else if (_sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP)
                        // we are actually read blocked in order to write
                        SslConnection.this.fillInterested();
                    else
                        // try the flush again
                        completeWrite();
                }
            }
        };

        public SslEndPoint()
        {
            super(getEndPoint().getLocalAddress(), getEndPoint().getRemoteAddress());
        }

        public SslConnection getSslConnection()
        {
            return SslConnection.this;
        }

        @Override
        public <C> void fillInterested(C context, Callback<C> callback) throws IllegalStateException
        {
            _readInterest.register(context, callback);
        }

        @Override
        public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
        {
            _writeFlusher.write(context, callback, buffers);
        }

        @Override
        public synchronized int fill(ByteBuffer buffer) throws IOException
        {
            LOG.debug("{} fill enter", SslConnection.this);
            try
            {
                // Do we already have some decrypted data?
                if (BufferUtil.hasContent(_appIn))
                    return BufferUtil.append(_appIn, buffer);

                // We will need a network buffer
                if (_netIn == null)
                    _netIn = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _netDirect);
                else
                    BufferUtil.compact(_netIn);

                // We also need an app buffer, but can use the passed buffer if it is big enough
                ByteBuffer app_in;
                if (BufferUtil.space(buffer) > _sslEngine.getSession().getApplicationBufferSize())
                    app_in = buffer;
                else if (_appIn == null)
                    app_in = _appIn = _bufferPool.acquire(_sslEngine.getSession().getApplicationBufferSize(), _appDirect);
                else
                    app_in = _appIn;

                // loop filling and unwrapping until we have something
                while (true)
                {
                    // Let's try reading some encrypted data... even if we have some already.
                    int net_filled = getEndPoint().fill(_netIn);
                    LOG.debug("{} filled {} encrypted bytes", SslConnection.this, net_filled);
                    if (net_filled > 0)
                        _underflown = false;

                    // Let's try the SSL thang even if we have no net data because in that
                    // case we want to fall through to the handshake handling
                    int pos = BufferUtil.flipToFill(app_in);
                    _unwrapResult = _sslEngine.unwrap(_netIn, app_in);
                    LOG.debug("{} unwrap {}", SslConnection.this, _unwrapResult);
                    BufferUtil.flipToFlush(app_in, pos);

                    // and deal with the results
                    switch (_unwrapResult.getStatus())
                    {
                        case BUFFER_OVERFLOW:
                            throw new IllegalStateException();

                        case CLOSED:
                            // Dang! we have to care about the handshake state
                            switch (_sslEngine.getHandshakeStatus())
                            {
                                case NOT_HANDSHAKING:
                                    return -1;

                                case NEED_TASK:
                                    // run the task
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_WRAP:
                                    // we need to send some handshake data
                                    if (!_flushUnwrap)
                                    {
                                        _fillWrap = true;
                                        try
                                        {
                                            flush(BufferUtil.EMPTY_BUFFER);
                                        }
                                        catch(IOException e)
                                        {
                                            return -1;
                                        }
                                        if (BufferUtil.hasContent(_netOut))
                                            return 0;
                                        _fillWrap = false;
                                    }
                                    return -1;

                                default:
                                    throw new IllegalStateException();
                            }

                        case BUFFER_UNDERFLOW:
                            _underflown = true;

                            //$FALL-THROUGH$ to deal with handshaking stuff

                        default:
                            // if we produced bytes, we don't care about the handshake state
                            if (_unwrapResult.bytesProduced() > 0)
                            {
                                if (app_in == buffer)
                                    return _unwrapResult.bytesProduced();
                                return BufferUtil.append(_appIn, buffer);
                            }

                            // Dang! we have to care about the handshake state
                            switch (_sslEngine.getHandshakeStatus())
                            {
                                case NOT_HANDSHAKING:
                                    // we just didn't read anything.
                                    if (net_filled < 0)
                                        _sslEngine.closeInbound();
                                    return 0;

                                case NEED_TASK:
                                    // run the task
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_WRAP:
                                    // we need to send some handshake data
                                    if (_flushUnwrap)
                                        return 0;
                                    _fillWrap = true;
                                    flush(BufferUtil.EMPTY_BUFFER);
                                    if (BufferUtil.hasContent(_netOut))
                                        return 0;
                                    _fillWrap = false;
                                    continue;

                                case NEED_UNWRAP:
                                    // if we just filled some net data
                                    if (net_filled < 0)
                                        _sslEngine.closeInbound();
                                    else if (net_filled > 0)
                                        // maybe we will fill some more on a retry
                                        continue;
                                    // we need to wait for more net data
                                    return 0;

                                case FINISHED:
                                    throw new IllegalStateException();
                            }
                    }
                }
            }
            catch (SSLException e)
            {
                getEndPoint().close();
                LOG.debug(e);
                throw new EofException(e);
            }
            catch (Exception e)
            {
                getEndPoint().close();
                throw e;
            }
            finally
            {
                if (_netIn != null && !_netIn.hasRemaining())
                {
                    _bufferPool.release(_netIn);
                    _netIn = null;
                }
                if (_appIn != null && !_appIn.hasRemaining())
                {
                    _bufferPool.release(_appIn);
                    _appIn = null;
                }
                LOG.debug("{} fill exit", SslConnection.this);
            }
        }

        @Override
        public synchronized int flush(ByteBuffer... appOuts) throws IOException
        {
            // The contract for flush does not require that all appOuts bytes are written
            // or even that any appOut bytes are written!  If the connection is write block
            // or busy handshaking, then zero bytes may be taken from appOuts and this method
            // will return 0 (even if some handshake bytes were flushed and filled).
            // it is the applications responsibility to call flush again - either in a busy loop
            // or better yet by using AsyncEndPoint#write to do the flushing.
            
            LOG.debug("{} flush enter {}", SslConnection.this, appOuts);
            try
            {
                if (_netWriting)
                    return 0;

                // We will need a network buffer
                if (_netOut == null)
                    _netOut = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize() * 2, _netDirect);

                while (true)
                {
                    // do the funky SSL thang!
                    BufferUtil.compact(_netOut);
                    int pos = BufferUtil.flipToFill(_netOut);
                    _wrapResult = _sslEngine.wrap(appOuts, _netOut);
                    LOG.debug("{} wrap {}", SslConnection.this, _wrapResult);
                    BufferUtil.flipToFlush(_netOut, pos);

                    // and deal with the results
                    switch (_wrapResult.getStatus())
                    {
                        case CLOSED:
                            if (BufferUtil.hasContent(_netOut))
                            {
                                _netWriting = true;
                                getEndPoint().flush(_netOut);
                                if (BufferUtil.hasContent(_netOut))
                                    return 0;
                            }
                            if (_fillWrap)
                                return 0;
                            throw new EofException();

                        case BUFFER_UNDERFLOW:
                            throw new IllegalStateException();

                        case BUFFER_OVERFLOW:
                            if (LOG.isDebugEnabled())
                                LOG.debug("{} OVERFLOW {}", this, BufferUtil.toDetailString(_netOut));

                            //$FALL-THROUGH$
                        default:
                            // if we have net bytes, let's try to flush them
                            if (BufferUtil.hasContent(_netOut))
                            {
                                getEndPoint().flush(_netOut);
                                return _wrapResult.bytesConsumed();
                            }

                            // Dang! we have to deal with handshake state
                            switch (_sslEngine.getHandshakeStatus())
                            {
                                case NOT_HANDSHAKING:
                                    // we just didn't write anything. Strange?
                                    return 0;

                                case NEED_TASK:
                                    // run the task
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_WRAP:
                                    // Hey we just wrapped!
                                    continue;

                                case NEED_UNWRAP:
                                    // Were we were not called from fill and not reading anyway
                                    if (!_fillWrap && !_readInterest.isInterested())
                                    {
                                        _flushUnwrap = true;
                                        fill(BufferUtil.EMPTY_BUFFER);
                                    }
                                    return 0;

                                case FINISHED:
                                    throw new IllegalStateException();

                            }
                    }
                }
            }
            catch (Exception e)
            {
                getEndPoint().close();
                throw e;
            }
            finally
            {
                LOG.debug("{} flush exit", SslConnection.this);
                releaseNetOut();
            }
        }

        private void releaseNetOut()
        {
            if (_netOut != null && !_netOut.hasRemaining())
            {
                _bufferPool.release(_netOut);
                _netOut = null;
                if (_sslEngine.isOutboundDone())
                    getEndPoint().shutdownOutput();
            }
        }

        @Override
        public void shutdownOutput()
        {
            _sslEngine.closeOutbound();
            try
            {
                flush(BufferUtil.EMPTY_BUFFER);
            }
            catch (IOException e)
            {
                LOG.ignore(e);
                getEndPoint().close();
            }
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _sslEngine.isOutboundDone() || !getEndPoint().isOpen();
        }

        @Override
        public void close()
        {
            getEndPoint().close();
        }

        @Override
        public boolean isOpen()
        {
            return getEndPoint().isOpen();
        }

        @Override
        public Object getTransport()
        {
            return getEndPoint();
        }

        @Override
        public boolean isInputShutdown()
        {
            return _ishut;
        }

        @Override
        public AsyncConnection getAsyncConnection()
        {
            return _connection;
        }

        @Override
        public void setAsyncConnection(AsyncConnection connection)
        {
            _connection = connection;
        }

        @Override
        public String toString()
        {
            return String.format("%s{%s%s%s}", super.toString(), _readInterest.isInterested() ? "R" : "", _writeFlusher.isWritePending() ? "W" : "", _netWriting ? "w" : "");
        }

    }
}
