package io.padtap;

interface IPadListener {
    oneway void onState(in int[] buttons, in float[] axes) = 1;
    oneway void onDevices(String desc) = 2;
}
