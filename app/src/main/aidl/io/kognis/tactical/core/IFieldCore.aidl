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

    // ── Adaptive learning subsystem (Sprint S28) ──────────────────────────
    /** Start a training session. Returns sessionId as long; 0 = failure. */
    long startLearningSession(String curriculumUriString);
    /** Returns JSON snapshot of learner model (mastery, prefs, recent facts). */
    String getLearnerModelJson();
    /** End current training session: write summary, promote high-confidence facts. */
    void endLearningSession();
    /** Record a quiz outcome from the UI. correct=true|false. */
    void recordQuizOutcome(String topic, boolean correct);
}
