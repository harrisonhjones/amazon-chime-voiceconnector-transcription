package com.amazonaws.transcribestreaming;

import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kvstranscribestreaming.KVSUtils;
import org.apache.commons.lang3.Validate;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.transcribestreaming.model.AudioEvent;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This Subscription converts audio bytes received from the KVS stream into AudioEvents
 * that can be sent to the Transcribe service. It implements a simple demand system that will read chunks of bytes
 * from a KVS stream using the KVS parser library
 *
 * <p>Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.</p>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
public class KVSByteToAudioEventSubscription implements Subscription {

    private static final int CHUNK_SIZE_IN_KB = 4;
    private ExecutorService executor = Executors.newFixedThreadPool(1);
    private AtomicLong demand = new AtomicLong(0);
    private final Subscriber<? super AudioStream> subscriber;
    private final StreamingMkvReader streamingMkvReader;
    private String callId;
    private OutputStream outputStream;
    private final FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor;
    private final FragmentMetadataVisitor fragmentVisitor;
    private final boolean shouldWriteToOutputStream;

    final static byte ULAW_TABH[] = new byte[256];
    final static byte ULAW_TABL[] = new byte[256];


    /**
     * Initializes the decode tables
     */
    static {
        for (int i=0;i<256;i++) {
            int ulaw = ~i;
            int t;

            ulaw &= 0xFF;
            t = ((ulaw & 0xf)<<3) + 132;
            t <<= ((ulaw & 0x70) >> 4);
            t = ( (ulaw&0x80) != 0 ) ? (132-t) : (t-132);

            ULAW_TABL[i] = (byte) (t&0xff);
            ULAW_TABH[i] = (byte) ((t>>8) & 0xff);
        }
    }

    public KVSByteToAudioEventSubscription(Subscriber<? super AudioStream> s, StreamingMkvReader streamingMkvReader,
        String callId, OutputStream outputStream, FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor,
        FragmentMetadataVisitor fragmentVisitor, boolean shouldWriteToOutputStream) {
        this.subscriber = Validate.notNull(s);
        this.streamingMkvReader = Validate.notNull(streamingMkvReader);
        this.callId = Validate.notNull(callId);
        this.outputStream = Validate.notNull(outputStream);
        this.tagProcessor = Validate.notNull(tagProcessor);
        this.fragmentVisitor = Validate.notNull(fragmentVisitor);
        this.shouldWriteToOutputStream = shouldWriteToOutputStream;
    }

    @Override
    public void request(long n) {
        if (n <= 0) {
            subscriber.onError(new IllegalArgumentException("Demand must be positive"));
        }

        demand.getAndAdd(n);
        //We need to invoke this in a separate thread because the call to subscriber.onNext(...) is recursive
        executor.submit(() -> {
            try {
                while (demand.get() > 0) {
                    ByteBuffer audioBuffer = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor,
                        callId, CHUNK_SIZE_IN_KB);

                    if (audioBuffer.remaining() > 0) {

                        AudioEvent audioEvent = audioEventFromBuffer(audioBuffer);
                        subscriber.onNext(audioEvent);

                        if (shouldWriteToOutputStream)
                        {
                            //Write audioBytes to a temporary file as they are received from the stream
                            byte[] audioBytes = new byte[audioBuffer.remaining()];
                            audioBuffer.get(audioBytes);
                            outputStream.write(audioBytes);
                        }

                    } else {
                        subscriber.onComplete();
                        break;
                    }
                    demand.getAndDecrement();
                }
            } catch (Exception e) {
                subscriber.onError(e);
            }
        });
    }

    private ByteBuffer convertToSixteenBitPCM(ByteBuffer input)
    {
        byte tabByte1[] = ULAW_TABL;
        byte tabByte2[] = ULAW_TABH;
        byte[] inputArrary = new byte[input.remaining()];
        input.get(inputArrary);
        byte[] outputArray = new byte[inputArrary.length * 2];
        int j = 0;
        for(int i=0; i< inputArrary.length; i++)
        {
            outputArray[j] = (byte)tabByte1[inputArrary[i] & 0xFF];
            outputArray[j+1] = (byte)tabByte2[inputArrary[i] & 0xFF];
            j=j+2;
        }
        return ByteBuffer.wrap(outputArray);
    }

    @Override
    public void cancel() {
        executor.shutdown();
    }

    private AudioEvent audioEventFromBuffer(ByteBuffer bb) {
        return AudioEvent.builder()
                .audioChunk(SdkBytes.fromByteBuffer(bb))
                .build();
    }
}
