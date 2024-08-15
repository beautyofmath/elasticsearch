/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.xcontent;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.equalTo;

public class XContentGeneratorTests extends ESTestCase {

    public void testCopyCurrentEventRoundtrip() throws Exception {
        assertTypeCopy("integer", "1");
        assertTypeCopy("float", "1.0");
        assertTypeCopy("long", "5000000000");
        assertTypeCopy("double", "1.123456789");
        // assertTypeCopy("biginteger", "18446744073709551615");
        assertTypeCopy("bigdecimal", "1.1234567890123456789");
    }

    private void assertTypeCopy(String typename, String value) throws Exception {
        var input = String.format("{\"%s\":%s,\"%s_in_array\":[%s]}", typename, value, typename, value);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (
            var generator = JsonXContent.jsonXContent.createGenerator(outputStream);
            var parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, input)
        ) {
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                generator.copyCurrentEvent(parser);
            }
            generator.copyCurrentEvent(parser); // copy end object too
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8), equalTo(input));
    }
}
