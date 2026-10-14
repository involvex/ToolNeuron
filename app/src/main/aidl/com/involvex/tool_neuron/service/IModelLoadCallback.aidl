package com.involvex.tool_neuron.service;

interface IModelLoadCallback {
    void onSuccess(String modelInfoJson);
    void onError(String message);
}
