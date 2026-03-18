package org.dollos.service;

interface IDollOSService {
    /** Returns the DollOS version string */
    String getVersion();

    /** Returns true if the AI core is configured (API key set) */
    boolean isAiConfigured();

    /** Returns the path to the DollOS data directory */
    String getDataDirectory();

    /** Store API key configuration (called by OOBE wizard via Binder) */
    void setApiKey(String provider, String apiKey);

    /** Store GMS opt-in preference (called by OOBE wizard via Binder) */
    void setGmsOptIn(boolean optIn);

    /** Check if user opted into GMS */
    boolean isGmsOptedIn();

    /** Store AI personality configuration */
    void setPersonality(String name, String description);

    /** Get AI personality name */
    String getPersonalityName();
}
