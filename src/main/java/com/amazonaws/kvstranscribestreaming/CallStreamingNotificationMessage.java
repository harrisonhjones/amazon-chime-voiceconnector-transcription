package com.amazonaws.kvstranscribestreaming;

public class CallStreamingNotificationMessage {
    private String callId;
    private String streamName;
    private String fragmentNumber;

    public CallStreamingNotificationMessage() {
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public String getFragmentNumber() {
        return fragmentNumber;
    }

    public void setFragmentNumber(String fragmentNumber) {
        this.fragmentNumber = fragmentNumber;
    }
}
