package io.padtap;

import io.padtap.IPadListener;

interface IPadService {
    void destroy() = 16777114;
    void setListener(IPadListener l) = 1;
    void setGrab(boolean on) = 2;
    oneway void touchDown(int id, float x, float y) = 3;
    oneway void touchMove(int id, float x, float y) = 4;
    oneway void touchUp(int id) = 5;
    void releaseAll() = 6;
    String info() = 7;
}
