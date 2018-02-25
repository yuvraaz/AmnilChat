/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of WiFi WalkieTalkie application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.wfwt;

import android.util.Log;
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.Session;
import org.jsl.collider.StreamDefragger;
import org.jsl.collider.TimerQueue;
import com.example.walkitwaki.BuildConfig;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class ChannelSession implements Session.Listener
{
    private static final String LOG_TAG = "ChannelSession";

    private static final AtomicIntegerFieldUpdater<ChannelSession>
            s_totalBytesReceivedUpdater = AtomicIntegerFieldUpdater.newUpdater(
                    ChannelSession.class, "m_totalBytesReceived" );

    private final Channel m_channel;
    private final String m_serviceName;
    private final Session m_session;
    private final StreamDefragger m_streamDefragger;
    private final SessionManager m_sessionManager;
    private final AudioPlayer m_audioPlayer;
    private final TimerQueue m_timerQueue;
    private TimerHandler m_timerHandler;

    private volatile int m_totalBytesReceived;
    private int m_lastBytesReceived;
    private int m_pingTimeouts;
    private long m_pingSendTime;
    private long m_ping;

    private boolean m_sendAudioFrame;

    private String getLogPrefix()
    {
        return m_channel.getName() + " " + m_session.getRemoteAddress() + ": ";
    }

    private class TimerHandler implements Runnable
    {
        public void run()
        {
            handlePingTimeout();
        }
    }

    private void handlePingTimeout()
    {
        if (m_lastBytesReceived == m_totalBytesReceived)
        {
            if (++m_pingTimeouts == 10)
            {
                Log.i( LOG_TAG, getLogPrefix() + "connection timeout, closing connection." );
                m_session.closeConnection();
            }
        }
        else
        {
            m_lastBytesReceived = m_totalBytesReceived;
            m_pingTimeouts = 0;
        }

        Log.v( LOG_TAG, getLogPrefix() + "ping" );
        m_pingSendTime = System.currentTimeMillis();
        m_session.sendData( Protocol.Ping.create() );
    }

    private void handleMessage( RetainableByteBuffer msg )
    {
        final short messageID = Protocol.Message.getID( msg );
        switch (messageID)
        {
            case Protocol.AudioFrame.ID:
                final RetainableByteBuffer audioFrame = Protocol.AudioFrame.getAudioData( msg );
                m_audioPlayer.play( audioFrame );
                audioFrame.release();
            break;

            case Protocol.Ping.ID:
                m_session.sendData( Protocol.Pong.create() );
            break;

            case Protocol.Pong.ID:
                final long ping = (System.currentTimeMillis() - m_pingSendTime) / 2;
                if (Math.abs(ping - m_ping) > 10)
                {
                    m_ping = ping;
                    m_channel.setPing( m_serviceName, this, ping );
                }
            break;

            case Protocol.StationName.ID:
                try
                {
                    final String stationName = Protocol.StationName.getStationName( msg );
                    if (stationName.length() > 0)
                    {
                        if (m_serviceName == null)
                            m_channel.setStationName( this, stationName );
                        else
                            m_channel.setStationName( m_serviceName, stationName );
                    }
                }
                catch (final CharacterCodingException ex)
                {
                    Log.w( LOG_TAG, ex.toString(), ex );
                }
            break;

            default:
                Log.w( LOG_TAG, getLogPrefix() + "unexpected message " + messageID );
            break;
        }
    }

    public static StreamDefragger createStreamDefragger()
    {
        return new StreamDefragger( Protocol.Message.HEADER_SIZE )
        {
            protected int validateHeader( ByteBuffer header )
            {
                if (BuildConfig.DEBUG && (header.remaining() < Protocol.Message.HEADER_SIZE))
                    throw new AssertionError();
                final int messageLength = Protocol.Message.getLength(header);
                if (messageLength <= 0)
                    return -1; /* StreamDefragger.getNext() will return StreamDefragger.INVALID_HEADER */
                return messageLength;
            }
        };
    }

    public ChannelSession(
            Channel channel,
            String serviceName,
            Session session,
            StreamDefragger streamDefragger,
            SessionManager sessionManager,
            AudioPlayer audioPlayer,
            TimerQueue timerQueue,
            int pingInterval )
    {
        m_channel = channel;
        m_serviceName = serviceName;
        m_session = session;
        m_streamDefragger = streamDefragger;
        m_sessionManager = sessionManager;
        m_audioPlayer = audioPlayer;
        m_timerQueue = timerQueue;

        if (pingInterval > 0)
        {
            m_timerHandler = new TimerHandler();
            m_timerQueue.scheduleAtFixedRate(
                    m_timerHandler, pingInterval, pingInterval, TimeUnit.SECONDS );
        }

        m_sessionManager.addSession( this );
        // FIXME: check for possible message in the streamDefragger
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        final int bytesReceived = data.remaining();
        RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        while (msg != null)
        {
            if (msg == StreamDefragger.INVALID_HEADER)
            {
                Log.i( LOG_TAG, getLogPrefix() +
                        "invalid message received, close connection." );
                m_session.closeConnection();
                break;
            }
            else
            {
                handleMessage( msg );
                msg = m_streamDefragger.getNext();
            }
        }
        s_totalBytesReceivedUpdater.addAndGet( this, bytesReceived );
    }

    public void onConnectionClosed()
    {
        Log.i( LOG_TAG, getLogPrefix() + "connection closed" );

        if (m_timerHandler != null)
        {
            try
            {
                m_timerQueue.cancel( m_timerHandler );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString(), ex );
                Thread.currentThread().interrupt();
            }
            m_timerHandler = null;
        }

        m_channel.removeSession( m_serviceName, this );
        m_sessionManager.removeSession( this );
        m_audioPlayer.stopAndWait();
        m_streamDefragger.close();
    }

    public final int sendMessage( RetainableByteBuffer msg )
    {
        return m_session.sendData( msg );
    }

    public final void sendAudioFrame( RetainableByteBuffer audioFrame, boolean ptt )
    {
        if (ptt || m_sendAudioFrame)
            m_session.sendData( audioFrame );
    }

    public final void setSendAudioFrame( boolean sendAudioFrame )
    {
        m_sendAudioFrame = sendAudioFrame;
    }

    public SocketAddress getRemoteAddress()
    {
        return m_session.getRemoteAddress();
    }
}
