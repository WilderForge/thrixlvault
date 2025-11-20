/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wildermods.thrixlvault;

import com.wildermods.thrixlvault.exception.VersionParsingException;
import com.wildermods.thrixlvault.utils.version.SemanticVersionImpl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class VersionParsingTests {

    private void parseSemantic(String s, boolean storeX) throws VersionParsingException {
        new SemanticVersionImpl(s, storeX);
    }

    @Test
    void testValidSemanticVersionsWithoutX() throws VersionParsingException {
        // These should parse without throwing
        parseSemantic("0.3.5", false);
        parseSemantic("0.3.5-beta.2", false);
        parseSemantic("0.3.5-alpha.6+build.120", false);
        parseSemantic("0.3.5+build.3000", false);
        parseSemantic("1.0.0-0.3.7", false);
        parseSemantic("1.0.0-x.7.z.92", false);
        parseSemantic("1.0.0+20130313144700", false);
        parseSemantic("1.0.0-beta+exp.sha.5114f85", false);
    }

    @Test
    void testInvalidSemanticVersionsWithoutX() {
        // These should throw VersionParsingException
        assertThrows(VersionParsingException.class, () -> parseSemantic("0.0.-1", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("0." + ((long) Integer.MAX_VALUE + 1) + ".0", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("0.-1.0", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("-1.0.0", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("0.0.a", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("0.a.0", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("a.0.0", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.x", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.X", false));
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.*", false));
    }

    @Test
    void testValidSemanticVersionsWithX() throws VersionParsingException {
        // These should parse without throwing
        assertThrows(VersionParsingException.class, () -> parseSemantic("x", true));
        parseSemantic("2.x", true);
        parseSemantic("2.x.x", true);
        parseSemantic("2.X", true);
        parseSemantic("2.*", true);
    }

    @Test
    void testInvalidSemanticVersionsWithX() {
        // These should throw VersionParsingException
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.x.1", true));
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.*.1", true));
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.x-alpha.1", true));
        assertThrows(VersionParsingException.class, () -> parseSemantic("2.*-alpha.1", true));
        assertThrows(VersionParsingException.class, () -> parseSemantic("*-alpha.1", true));
    }
}


