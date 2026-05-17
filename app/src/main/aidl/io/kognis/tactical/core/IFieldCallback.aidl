// IFieldCallback.aidl
package io.kognis.tactical.core;

interface IFieldCallback {
    void onTokenRetrieved(String token);
    void onGenerationComplete(String fullHistoryJson);
    void onError(String error);
    void onStatusChange(String status);
    void onRagMetadata(String ragInfoJson);
    void onKbUpdateComplete(String resultJson);
    void onLoadingProgress(String stage);
}
