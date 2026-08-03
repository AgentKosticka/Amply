package com.agentkosticka.amply.shizuku.protocol;

import com.agentkosticka.amply.shizuku.protocol.PlaybackSessionParcel;
import com.agentkosticka.amply.shizuku.protocol.OperationResultParcel;

interface IVolumeService {
    int getProtocolVersion();
    long getCapabilities();
    List<PlaybackSessionParcel> getActivePlaybacks();
    OperationResultParcel setPlayerVolume(int playerInterfaceId, float volume);
    int[] getStreamTopology();
    OperationResultParcel setSystemStreamVolume(int streamType, int index);
    int applyRingerExperiment(int method, int target, int restoreVolume);
    void destroy();
}
