/*
 * Copyright (C) 2018 Tim Cadogan-Cowper.
 *
 * This file is part of Fabularium.
 *
 * Fabularium is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Fabularium; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.luxlunae.glk;

import android.content.Context;
import androidx.annotation.NonNull;
import android.util.Log;

public final class GLKLogger {
    private static final String TAG = "GLK";

    public static synchronized void initialise(@NonNull Context c) {
        // No-op: Android logging is always available
    }

    public static synchronized void flush() {
        // No-op: Android Log doesn't need flushing
    }

    public static synchronized void shutdown() {
        // No-op: Android Log doesn't need shutdown
    }

    public static synchronized void debug(@NonNull String s) {
        Log.d(TAG, s);
    }

    public static synchronized void error(@NonNull String s) {
        Log.e(TAG, s);
    }

    public static synchronized void warn(@NonNull String s) {
        Log.w(TAG, s);
    }
}
