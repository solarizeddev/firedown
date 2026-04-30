package com.solarized.firedown.manager;

public enum ServiceActions {

    DUMMY(0), AUDIO_ENCODE(1), ENCRYPTION(2), DECRYPTION(3), CANCEL_AUDIO_ENCODE(4), ERROR_AUDIO_ENCODE(5);

    private final int value;

    private ServiceActions(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static ServiceActions getType(int type){
        for(ServiceActions taskAction : ServiceActions.values()){
            if(taskAction.value == type)
                return taskAction;
        }
        return DUMMY;
    }

}
