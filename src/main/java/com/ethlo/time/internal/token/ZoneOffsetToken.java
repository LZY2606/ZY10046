package com.ethlo.time.internal.token;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 - 2024 Morten Haraldsen (ethlo)
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.text.ParsePosition;

import com.ethlo.time.Field;
import com.ethlo.time.internal.util.ParseCursor;
import com.ethlo.time.token.DateTimeToken;

public class ZoneOffsetToken implements DateTimeToken
{
    @Override
    public int read(final String text, final ParsePosition parsePosition)
    {
        final int start = parsePosition.getIndex();
        final long packed = ParseCursor.parseZoneOffset(text, start);
        if (packed == ParseCursor.ZONE_OFFSET_ABSENT)
        {
            return -1;
        }
        parsePosition.setIndex(start + ParseCursor.zoneOffsetConsumed(packed));
        return ParseCursor.zoneOffsetHours(packed) * 3600 + ParseCursor.zoneOffsetMinutes(packed) * 60;
    }

    @Override
    public Field getField()
    {
        return Field.ZONE_OFFSET;
    }
}
