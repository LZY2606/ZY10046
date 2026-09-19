package com.ethlo.time.internal;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 - 2025 Morten Haraldsen @ethlo
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

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Prints the display names of the behaviour-guard tests added for the shared cursor/failure
 * refactoring, so they are visible even in quiet ({@code mvn -q test}) output. Registered via
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.
 */
public class GuardTestNameListener implements TestExecutionListener
{
    private static final String MARKER = "[behavior-guard] ";

    @Override
    public void executionStarted(final TestIdentifier testIdentifier)
    {
        if (testIdentifier.isTest() && isGuardTest(testIdentifier))
        {
            System.out.println(MARKER + testIdentifier.getDisplayName());
        }
    }

    private static boolean isGuardTest(final TestIdentifier testIdentifier)
    {
        return testIdentifier.getUniqueId().contains("SharedParserBehaviorGuardTest")
                || testIdentifier.getUniqueId().contains("CursorTest");
    }
}
