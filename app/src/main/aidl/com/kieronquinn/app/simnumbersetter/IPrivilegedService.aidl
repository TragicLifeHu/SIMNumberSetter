package com.kieronquinn.app.simnumbersetter;

interface IPrivilegedService {

    void destroy() = 16777114;

    int runCommand(in String[] command) = 1;

}
