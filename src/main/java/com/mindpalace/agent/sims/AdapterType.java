package com.mindpalace.agent.sims;

/**
 * LoRA adapter types for different tasks. Ported from SIMS1337.
 * Each type maps to a specialized weight set switched in <100ms.
 */
public enum AdapterType {
    CHAT,       // social interactions, conversations
    CODE,       // code generation, analysis
    PATHFIND,   // navigation, pathfinding decisions
    MOTIVES,    // motive logic, need fulfillment
    CAREER,     // career decisions, life goals
    ANALYSIS    // general analysis, decomposition
}
