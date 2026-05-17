// IFieldCore.aidl
package io.kognis.tactical.core;

import io.kognis.tactical.core.IFieldCallback;

interface IFieldCore {
    void registerCallback(IFieldCallback cb);
    void unregisterCallback(IFieldCallback cb);
    void sendQuery(String query, String ragMode);
    void cancelGeneration();
    boolean isModelReady();
    void switchModel(String modelName, String quantization);
    void setVerbosity(String level);
    void setLanguage(String lang);
    void enableInternetAndRetry();
    void updateKnowledgeBase(String uriString);
    void restoreKnowledgeBase();
    void clearConversation();
}
