package com.kieronquinn.app.simnumbersetter;

interface IPhoneNumberSetter {

    int[] getActivePhoneIds();
    int getDefaultPhoneId();
    int setLine1Number(int phoneId, String number);
    String getLine1Number(int phoneId);

}
