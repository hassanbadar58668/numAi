package io.github.gohoski.numai.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.numai.R;
import io.github.gohoski.numai.data.ConfigManager;
import io.github.gohoski.numai.model.Message;
import io.github.gohoski.numai.model.Role;

public class ApiService {
    private final ApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConfigManager config;
    private Context ctx;

    public ApiService(Context context) {
        this.apiClient = new ApiClient(context);
        this.config = ConfigManager.getInstance(context);
        ctx = context;
    }

    public void chatCompletion(final List<Message> msg, final boolean thinking, final ApiCallback<ApiResult> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ApiRequest request = new ApiRequest("/chat/completions", "POST");
                    JSONArray messages = new JSONArray();
                    String systemStr = config.getConfig().getSystemPrompt();
                    if (systemStr.length() != 0) {
                        JSONObject system = new JSONObject();
                        system.put("role", "system");
                        system.put("content", systemStr);
                        messages.add(system);
                    }
                    boolean hasImg = false;
                    for (Message message : msg) {
                        JSONObject messageJson = new JSONObject();
                        messageJson.put("role", message.getRole());

                        if (message.getRoleEnum() == Role.TOOL) {
                            messageJson.put("tool_call_id", message.getToolCallId());
                            messageJson.put("content", message.getContent());
                        } else if (message.getToolCalls() != null) {
                            messageJson.put("tool_calls", message.getToolCalls());
                            // Standard OpenAI API expects content to be null or empty when sending tool calls back
                            messageJson.put("content", (String) null);
                        } else {
                            List<String> inputImages = message.getInputImages();
                            String fileContent = message.getFileContent();
                            
                            if ((inputImages != null && !inputImages.isEmpty()) || (fileContent != null && fileContent.length() > 0)) {
                                hasImg = true;
                                JSONArray content = new JSONArray();
                                
                                // 1. النص الأساسي
                                JSONObject inputText = new JSONObject();
                                inputText.put("type", "text");
                                inputText.put("text", message.getContent());
                                content.add(inputText);
                                
                                // 2. الصور المرفقة
                                if (inputImages != null && !inputImages.isEmpty()) {
                                    for (String image: inputImages) {
                                        JSONObject input = new JSONObject();
                                        input.put("type", "image_url");
                                        JSONObject imageUrl = new JSONObject();
                                        imageUrl.put("url", image);
                                        input.put("image_url", imageUrl);
                                        content.add(input);
                                    }
                                }
                                
                                // 3. محتوى الملف النصي المرفق (جديد)
                                if (fileContent != null && fileContent.length() > 0) {
                                    JSONObject fileInput = new JSONObject();
                                    fileInput.put("type", "text");
                                    fileInput.put("text", "\n\n--- بداية الملف المرفق ---\n" + fileContent + "\n--- نهاية الملف المرفق ---\n");
                                    content.add(fileInput);
                                }
                                
                                messageJson.put("content", content);
                            } else {
                                messageJson.put("content", message.getContent());
                            }
                        }
                        messages.add(messageJson);
                    }
                    JSONObject body = new JSONObject();
                    final String model = thinking ? config.getConfig().getThinkingModel() : config.getConfig().getChatModel();
                    body.put("model", model);
                    body.put("messages", messages);
                    body.put("stream", true);

                    if (config.getConfig().isWebSearchEnabled()) {
                        JSONArray tools = new JSONArray();
                        JSONObject tool = new JSONObject();
                        tool.put("type", "function");

                        JSONObject function = new JSONObject();
                        function.put("name", "web_search");
                        function.put("description", "Mandatory tool to fetch real-time facts, tech tutorials, and software compatibility info. Must be executed prior to answering any factual or technical user query.");

                        JSONObject parameters = new JSONObject();
                        parameters.put("type", "object");

                        JSONObject properties = new JSONObject();
                        JSONObject queryProp = new JSONObject();
                        queryProp.put("type", "string");
                        queryProp.put("description", "Search query keywords");
                        properties.put("query", queryProp);

                        parameters.put("properties", properties);
                        JSONArray required = new JSONArray();
                        required.add("query");
                        parameters.put("required", required);

                        function.put("parameters", parameters);
                        tool.put("function", function);
                        tools.add(tool);

                        body.put("tools", tools);
                    }

                    if (thinking) {
                        switch (config.getConfig().getBaseUrl()) {
                            case "https://openrouter.ai/api/v1":
                                JSONObject reasoning = new JSONObject();
                                reasoning.put("enabled", true);
                                body.put("reasoning", reasoning);
                                break;
                            case "https://api.together.xyz/v1":
                                JSONObject kw = new JSONObject();
                                kw.put("thinking", true);
                                body.put("chat_template_kwargs", kw);
                                break;
                            case "https://dashscope.aliyuncs.com/compatible-mode/v1":
                            case "https://dashscope-intl.aliyuncs.com/compatible-mode/v1":
                                body.put("enable_thinking", true); break;
                            default:
                                body.put("reasoning_effort", "high");
                        }
                    }
                    request.setBody(body.toString());

                    ApiResponse response = apiClient.execute(request);
                    if (response.isSuccessful()) {
                        deliverSuccess(callback, new ApiResult(model, response.getBody()));
                    } else {
                        String errorBody = "no body";
                        try {
                            errorBody = apiClient.readInputStreamToString(response.getBody());
                        } catch(IOException ignored) {}
                        deliverError(callback, new ApiError(ctx.getString(hasImg ? R.string.fail_send_vision : R.string.fail_send, response.getStatusCode() + " " + errorBody)));
                    }
                } catch (ApiError e) {
                    deliverError(callback, e);
                }
            }
        }).start();
    }

    public void getModels(final ApiCallback<ArrayList<String>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ApiRequest request = new ApiRequest("/models", "GET");
                    String response = apiClient.executeAsString(request);
                    ArrayList<String> models = new ArrayList<String>();

                    JSONObject resp = JSON.getObject(response);
                    if (resp.has("error"))
                        deliverError(callback, new ApiError(resp.getObject("error").getString("message")));
                    else {
                        JSONArray json = resp.getArray("data");
                        for (int i = 0; i < json.size(); i++) {
                            JSONObject model = json.getObject(i);
                            if (model.has("endpoints")) {
                                if (model.getArray("endpoints").has("/v1/chat/completions"))
                                    models.add(json.getObject(i).getString("id"));
                            } else
                                models.add(json.getObject(i).getString("id"));
                        }
                        deliverSuccess(callback, models);
                    }
                } catch (ApiError e) {
                    deliverError(callback, e);
                }
            }
        }).start();
    }

    private <T> void deliverSuccess(final ApiCallback<T> callback, final T result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(result);
            }
        });
    }

    private <T> void deliverError(final ApiCallback<T> callback, final ApiError error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                error.printStackTrace();
                callback.onError(error);
            }
        });
    }
}
