package io.github.gohoski.numai.model;

import java.util.ArrayList;
import java.util.List;

import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;

public class Message {
    private Role role = Role.USER;
    private String content = "";
    private String llm;
    private List<String> inputImages;
    private boolean isError = false;
    private String fileContent; // محتوى الملف النصي المرفق
    private JSONArray toolCalls;
    private String toolCallId;
    private int searchResultCount = -1;

    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
    }
    public Message(Role role, String content, String llm) {
        this.role = role;
        this.content = content;
        this.llm = llm;
    }
    public Message(Role role, String content, List<String> inputImages, String llm) {
        this.role = role;
        this.content = content;
        this.llm = llm;
        this.inputImages = inputImages;
    }

    public String getRole() {
        return role.toString();
    }

    public Role getRoleEnum() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getLlm() {
        return llm;
    }

    public List<String> getInputImages() {
        return inputImages;
    }

    public JSONArray getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(JSONArray toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public int getSearchResultCount() {
        return searchResultCount;
    }

    public void setSearchResultCount(int searchResultCount) {
        this.searchResultCount = searchResultCount;
    }

    public void updateContent(String additionalContent) {
        this.content += additionalContent;
    }

    public void setContent(String newContent) {
        this.content = newContent;
    }

    public boolean isSent() {
        return role == Role.USER;
    }

    public void setAsError() {
        isError = true;
    }

    // ===== الدوال الجديدة لملف Markdown =====
    public String getFileContent() {
        return fileContent;
    }

    public void setFileContent(String fileContent) {
        this.fileContent = fileContent;
    }
    // ======================================

    public JSONObject toJSONObject() {
        JSONObject json = new JSONObject();
        json.put("role", role.toString());
        json.put("content", content);
        if (llm != null) json.put("llm", llm);
        if (inputImages != null && !inputImages.isEmpty()) {
            JSONArray imgArr = new JSONArray();
            for (int i = 0; i < inputImages.size(); i++) {
                imgArr.add(inputImages.get(i));
            }
            json.put("inputImages", imgArr);
        }
        // حفظ محتوى الملف النصي
        if (fileContent != null && fileContent.length() > 0) {
            json.put("fileContent", fileContent);
        }
        if (toolCalls != null) json.put("toolCalls", toolCalls);
        if (toolCallId != null) json.put("toolCallId", toolCallId);
        if (searchResultCount >= 0) json.put("searchResultCount", searchResultCount);
        json.put("isError", isError);
        return json;
    }

    public static Message fromJSONObject(JSONObject json) {
        String roleStr = json.getNullableString("role");
        Role role = Role.USER;
        if ("assistant".equals(roleStr)) {
            role = Role.ASSISTANT;
        } else if ("system".equals(roleStr)) {
            role = Role.SYSTEM;
        } else if ("tool".equals(roleStr)) {
            role = Role.TOOL;
        }

        String content = json.getNullableString("content");
        if (content == null) content = "";

        String llm = json.getNullableString("llm");

        List<String> inputImages = new ArrayList<String>();
        if (json.has("inputImages")) {
            JSONArray imgArr = json.getNullableArray("inputImages");
            if (imgArr != null) {
                for (int i = 0; i < imgArr.size(); i++) {
                    inputImages.add(imgArr.getString(i));
                }
            }
        }

        // قراءة محتوى الملف النصي
        String fileContent = json.getNullableString("fileContent");

        Message message = new Message(role, content, inputImages, llm);
        message.setFileContent(fileContent);

        if (json.has("toolCalls")) {
            message.setToolCalls(json.getArray("toolCalls"));
        }
        if (json.has("toolCallId")) {
            message.setToolCallId(json.getNullableString("toolCallId"));
        }
        if (json.has("searchResultCount")) {
            message.setSearchResultCount(json.getInt("searchResultCount", -1));
        }
        if (json.getBoolean("isError", false)) {
            message.setAsError();
        }
        return message;
    }
}
