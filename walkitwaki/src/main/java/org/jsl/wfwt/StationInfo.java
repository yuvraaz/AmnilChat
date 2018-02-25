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

public class StationInfo
{
    public final String name;
    public final String addr;
    public final int transmission;
    public final long ping;
    public final ChannelSession session;

    public StationInfo( String name, String addr, int transmission, long ping, ChannelSession session )
    {
        this.name = name;
        this.addr = addr;
        this.transmission = transmission;
        this.ping = ping;
        this.session = session;
    }
}
