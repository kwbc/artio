/*
 * Copyright 2020 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.ilink;

import org.agrona.LangUtil;
import org.agrona.collections.Int2IntHashMap;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.IrDecoder;
import uk.co.real_logic.sbe.ir.Signal;
import uk.co.real_logic.sbe.ir.Token;

import java.util.List;

public class ILink3Offsets extends AbstractILink3Offsets
{
    private final Int2IntHashMap templateIdToSeqNumOffset = new Int2IntHashMap(MISSING_OFFSET);
    private final Int2IntHashMap templateIdToPossRetransOffset = new Int2IntHashMap(MISSING_OFFSET);

    public static final String SBE_IR_FILE = "ilinkbinary.sbeir";

    public static final int SEQ_NUM_ID = 9726;
    public static final int POSS_RETRANS_ID = 9765;

    public ILink3Offsets()
    {
        try
        {
            /*final Path directory = Paths.get(Negotiate500Encoder.class.getResource(".").toURI());
            final String irPath = directory.getParent().resolveSibling(SBE_IR_FILE).toAbsolutePath().toString();
            System.out.println(irPath);*/

            // TODO: remove hardcoding
            final String irPath = System.getProperty("user.home") +
                "/Projects/artio/artio-ilink3-codecs/build/generated-src/ilinkbinary.sbeir";

            try (IrDecoder irDecoder = new IrDecoder(irPath))
            {
                final Ir ir = irDecoder.decode();
                ir.messages().forEach(messageTokens ->
                {
                    final Token beginMessage = messageTokens.get(0);
                    final int templateId = beginMessage.id();
                    findOffset(messageTokens, templateId, SEQ_NUM_ID, templateIdToSeqNumOffset);
                    findOffset(messageTokens, templateId, POSS_RETRANS_ID, templateIdToPossRetransOffset);
                });
            }
        }
        catch (final Exception e)
        {
            LangUtil.rethrowUnchecked(e);
        }
    }

    private static void findOffset(
        final List<Token> messageTokens,
        final int templateId,
        final int fieldId,
        final Int2IntHashMap templateIdToFieldOffset)
    {
        messageTokens
            .stream()
            .filter(token -> token.id() == fieldId && token.signal() == Signal.BEGIN_FIELD)
            .findFirst()
            .ifPresent(seqNum ->
            templateIdToFieldOffset.put(templateId, seqNum.offset()));
    }

    public int seqNumOffset(final int templateId)
    {
        return templateIdToSeqNumOffset.get(templateId);
    }

    public int possRetransOffset(final int templateId)
    {
        return templateIdToPossRetransOffset.get(templateId);
    }
}