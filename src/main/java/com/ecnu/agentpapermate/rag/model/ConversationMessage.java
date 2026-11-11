package com.ecnu.agentpapermate.rag.model;

/** 历史对话仅携带角色和正文，用于理解追问，不作为本轮论文证据。 */
public record ConversationMessage(String role, String content) {
}
