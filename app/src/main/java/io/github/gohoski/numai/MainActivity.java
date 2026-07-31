package io.github.gohoski.numai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.text.ClipboardManager;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.numai.api.ApiCallback;
import io.github.gohoski.numai.api.ApiError;
import io.github.gohoski.numai.api.ApiResult;
import io.github.gohoski.numai.api.ApiService;
import io.github.gohoski.numai.data.ChatManager;
import io.github.gohoski.numai.data.ConfigManager;
import io.github.gohoski.numai.data.MessageManager;
import io.github.gohoski.numai.model.Chat;
import io.github.gohoski.numai.model.Message;
import io.github.gohoski.numai.model.Role;
import io.github.gohoski.numai.search.SearchEngine;
import io.github.gohoski.numai.search.SearchManager;
import io.github.gohoski.numai.search.SearchResult;
import io.github.gohoski.numai.ui.MarkdownParser;
import io.github.gohoski.numai.ui.MessageAdapter;
import io.github.gohoski.numai.util.Base64;
import io.github.gohoski.numai.util.SSLDisabler;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_PICK_IMAGE = 1;

    private ApiService apiService;
    private ConfigManager config;

    private ListView msgList;
    private EditText input;
    private MessageAdapter adapter;
    private ImageButton sendBtn;
    private ToggleButton thinkingToggle;
    private ProgressBar progressBar;
    private TextView imgCount;
    private ImageButton attachBtn;
    private boolean autoScroll = true;
    private boolean isGenerating = false;
    private boolean isThinkingState = false;
    private InputStream currentStream;
    private volatile boolean isCancelled = false;
    int UPDATE_DELAY_MS = 250;

    private final StringBuilder thinkBuffer = new StringBuilder();
    private final StringBuilder contentBuffer = new StringBuilder();
    private final List<String> inputImages = new ArrayList<String>();

    private static class StreamToolCall {
        String id = "";
        String name = "";
        StringBuilder arguments = new StringBuilder();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SSLDisabler.disableSSLCertificateChecking();

        config = ConfigManager.getInstance(this);
        if (config.getConfig().getApiKey().length() == 0) {
            startActivity(new Intent(this, FirstTimeActivity.class));
            finish();
            return;
        }
        UPDATE_DELAY_MS = config.getConfig().getUpdateDelay();

        ChatManager.getInstance().loadChats(this);
        ChatManager.getInstance().startNewChat();

        apiService = new ApiService(this);
        msgList = (ListView) findViewById(R.id.messages_list);
        input = (EditText) findViewById(R.id.message_input);
        sendBtn = (ImageButton) findViewById(R.id.send_button);
        attachBtn = (ImageButton) findViewById(R.id.attach_button);
        thinkingToggle = (ToggleButton) findViewById(R.id.thinking);
        progressBar = (ProgressBar) findViewById(R.id.waiting);
        imgCount = (TextView) findViewById(R.id.img_count);

        sendBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (isGenerating) {
                    stopGeneration();
                } else {
                    sendMessage();
                }
            }
        });

        attachBtn.setOnClickListener(new OnClickListener() {
    public void onClick(View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "text/plain"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "اختر صورة أو ملف Markdown"), REQUEST_CODE_PICK_IMAGE);
    }
});attachBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, getString(R.string.select_picture)), REQUEST_CODE_PICK_IMAGE);
            }
        });

        adapter = new MessageAdapter(this, MessageManager.getInstance().getMessages());
        msgList.setAdapter(adapter);
        scrollToBottom();

        if (config.getConfig().getShrinkThink()) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) thinkingToggle.getLayoutParams();
            params.bottomMargin = (int) (3 * getResources().getDisplayMetrics().density + 0.5f);
            thinkingToggle.setLayoutParams(params);
        }

        msgList.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL || scrollState == SCROLL_STATE_FLING) {
                    autoScroll = false;
                }
            }
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {}
        });
        final Context ctx = this;
        msgList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setText(((TextView) view.findViewById(R.id.message_text)).getText());
                Toast.makeText(ctx, R.string.text_copied, Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.chats:
                showChatsDialog();
                return true;
            case R.id.settings:
                startActivity(new Intent(this, SettingsActivity.class));
                finish();
                return true;
            case R.id.about:
                Toast.makeText(this, "numAi " + BuildConfig.VERSION_NAME + " (" + BuildConfig.BUILD_TYPE + ") \u25b6\ngithub.com/gohoski/numAi", Toast.LENGTH_SHORT).show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showChatsDialog() {
        final List<Chat> sortedChats = ChatManager.getInstance().getSortedChats();
        List<String> optionsList = new ArrayList<String>();
        optionsList.add(getString(R.string.new_chat));
        for (int i = 0; i < sortedChats.size(); i++) {
            optionsList.add(sortedChats.get(i).getTitle());
        }
        final String[] options = optionsList.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle(R.string.chats)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            ChatManager.getInstance().startNewChat();
                            refreshMessageAdapter();
                        } else {
                            final Chat selectedChat = sortedChats.get(which - 1);
                            showChatOptionsDialog(selectedChat);
                        }
                    }
                })
                .show();
    }

    private void showChatOptionsDialog(final Chat chat) {
        String[] actions = new String[]{getString(R.string.open), getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(chat.getTitle())
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            ChatManager.getInstance().setCurrentChat(chat);
                            refreshMessageAdapter();
                        } else if (which == 1) {
                            ChatManager.getInstance().deleteChat(MainActivity.this, chat);
                            refreshMessageAdapter();
                        }
                    }
                })
                .show();
    }

    private void refreshMessageAdapter() {
        autoScroll = true;
        adapter = new MessageAdapter(this, MessageManager.getInstance().getMessages());
        msgList.setAdapter(adapter);
        scrollToBottom();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            processSelectedImage(data.getData());
        }
    }

    private void processSelectedImage(Uri uri) {
        try {
            Bitmap bitmap = decodeSampledBitmap(this, uri, 1080, 1080);
            if (bitmap != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] bytes = baos.toByteArray();
                bitmap.recycle();

                inputImages.add("data:image/jpeg;base64," + Base64.encode(bytes));
                imgCount.setVisibility(View.VISIBLE);
                imgCount.setText(String.valueOf(inputImages.size()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
        }
    }

    private void scrollToBottom() {
        if (!autoScroll) return;
        msgList.post(new Runnable() {
            public void run() {
                msgList.post(new Runnable() {
                    public void run() {
                        adapter.notifyDataSetChanged();
                        int count = adapter.getCount();
                        if (count > 0) {
                            msgList.setSelection(count - 1);
                        }
                    }
                });
            }
        });
    }

    private void stopGeneration() {
        isCancelled = true;

        List<Message> msgs = MessageManager.getInstance().getMessages();
        int size = msgs.size();
        if (size > 0 && msgs.get(size - 1).getRole().equals(Role.ASSISTANT.toString())) {
            msgs.remove(size - 1);
            size--;
        }
        if (size > 0 && msgs.get(size - 1).getRole().equals(Role.USER.toString())) {
            msgs.remove(size - 1);
        }
        resetUIState();
        ChatManager.getInstance().onMessageAdded(this);

        runOnUiThread(new Runnable() {
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.length() == 0 && inputImages.isEmpty()) return;
        autoScroll = true;
        isCancelled = false;
        currentStream = null;
        MessageManager.getInstance().addMessage(new Message(Role.USER, text, new ArrayList<String>(inputImages), null));
        ChatManager.getInstance().onMessageAdded(this);

        input.setText("");
        sendBtn.setImageResource(R.drawable.ic_action_stop);
        input.setEnabled(false);
        attachBtn.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        inputImages.clear();
        imgCount.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        scrollToBottom();

        requestAICompletion();
    }

    private void requestAICompletion() {
        thinkBuffer.setLength(0);
        contentBuffer.setLength(0);
        isThinkingState = false;
        isGenerating = true;
        final boolean thinkingEnabled = thinkingToggle.isChecked();

        apiService.chatCompletion(MessageManager.getInstance().getMessages(), thinkingEnabled, new ApiCallback<ApiResult>() {
            @Override
            public void onSuccess(final ApiResult apiResult) {
                if (isCancelled) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        startResponseStream(apiResult.getResult(), apiResult.getModel(), thinkingEnabled);
                    }
                });
            }
            @Override
            public void onError(final ApiError error) {
                if (isCancelled) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        handleStreamError(error.getMessage());
                    }
                });
            }
        });
    }

    private void startResponseStream(final InputStream stream, String model, final boolean thinkingEnabled) {
        this.currentStream = stream;
        progressBar.setVisibility(View.GONE);
        final Message msg = new Message(Role.ASSISTANT, "", model);
        MessageManager.getInstance().addMessage(msg);
        ChatManager.getInstance().onMessageAdded(this);
        adapter.notifyDataSetChanged();
        new Thread(new Runnable() {
            public void run() {
                try {
                    readStream(stream, msg, thinkingEnabled);
                } catch (Exception e) {
                    if (isGenerating && !isCancelled) {
                        final String err = e.getMessage();
                        runOnUiThread(new Runnable() { public void run() { handleStreamError(err); } });
                    }
                }
            }
        }).start();
    }

    private void handleStreamError(String errorMsg) {
        progressBar.setVisibility(View.GONE);
        Message error = new Message(Role.ASSISTANT, errorMsg, getString(R.string.error));
        error.setAsError();
        MessageManager.getInstance().addMessage(error);
        ChatManager.getInstance().onMessageAdded(this);
        resetUIState();
    }

    private void resetUIState() {
        isGenerating = false;
        adapter.notifyDataSetChanged();
        autoScroll = true;
        scrollToBottom();
        sendBtn.setImageResource(android.R.drawable.ic_menu_send);
        input.setEnabled(true);
        attachBtn.setEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private void readStream(InputStream inputStream, final Message msg, final boolean thinkingEnabled) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"), 8192);
        String line;
        long lastUpdateTime = 0;
        List<StreamToolCall> streamToolCalls = new ArrayList<StreamToolCall>();

        while (!isCancelled && (line = reader.readLine()) != null) {
            if (!line.startsWith("data: ")) continue;
            String jsonData = line.substring(6).trim();
            if ("[DONE]".equals(jsonData)) break;
            try {
                JSONObject delta = JSON.getObject(jsonData).getArray("choices").getObject(0).getObject("delta");

                if (delta.has("tool_calls")) {
                    JSONArray tcArr = delta.getArray("tool_calls");
                    for (int i = 0; i < tcArr.size(); i++) {
                        JSONObject tcObj = tcArr.getObject(i);
                        int index = tcObj.getInt("index", 0);
                        while (streamToolCalls.size() <= index) {
                            streamToolCalls.add(new StreamToolCall());
                        }
                        StreamToolCall stc = streamToolCalls.get(index);
                        if (tcObj.has("id")) {
                            stc.id = tcObj.getString("id");
                        }
                        if (tcObj.has("function")) {
                            JSONObject fnObj = tcObj.getObject("function");
                            if (fnObj.has("name")) {
                                stc.name = fnObj.getString("name");
                            }
                            if (fnObj.has("arguments")) {
                                stc.arguments.append(fnObj.getString("arguments"));
                            }
                        }
                    }
                }

                String contentStr = null;
                try {
                    contentStr = delta.getString("content");
                } catch(JSONException ignored) {}
                String reasoningStr = extractJSONReasoning(delta);
                boolean hasUpdates = false;

                if (thinkingEnabled) {
                    if (reasoningStr != null && reasoningStr.length() > 0) {
                        thinkBuffer.append(reasoningStr);
                        hasUpdates = true;
                    }
                    if (contentStr != null && contentStr.length() > 0) {
                        processContentWithTags(contentStr);
                        hasUpdates = true;
                    }
                } else {
                    if (contentStr != null && contentStr.length() > 0) {
                        processContentWithTags(contentStr);
                        hasUpdates = true;
                    }
                }

                long currentTime = System.currentTimeMillis();
                if (hasUpdates && (currentTime - lastUpdateTime >= UPDATE_DELAY_MS)) {
                    lastUpdateTime = currentTime;
                    updateStreamUI(msg, thinkingEnabled, false);
                }
            } catch (Exception e) {
                Log.e("readStream", "error parsing chunk " + jsonData, e);
            }
        }
        if (isCancelled) return;

        final List<StreamToolCall> finalToolCalls = streamToolCalls;
        runOnUiThread(new Runnable() {
            public void run() {
                updateStreamUI(msg, thinkingEnabled, true);
                if (!finalToolCalls.isEmpty()) {
                    executeToolCalls(msg, finalToolCalls);
                } else {
                    ChatManager.getInstance().onMessageAdded(MainActivity.this);
                    resetUIState();
                }
            }
        });
    }

    private void executeToolCalls(final Message assistantMsg, final List<StreamToolCall> toolCalls) {
        JSONArray toolCallsArray = new JSONArray();
        final List<String> queriesToSearch = new ArrayList<String>();
        final List<String> callIds = new ArrayList<String>();

        for (int i = 0; i < toolCalls.size(); i++) {
            StreamToolCall stc = toolCalls.get(i);
            JSONObject tcJson = new JSONObject();
            tcJson.put("id", stc.id);
            tcJson.put("type", "function");

            JSONObject fnJson = new JSONObject();
            fnJson.put("name", stc.name);
            fnJson.put("arguments", stc.arguments.toString());
            tcJson.put("function", fnJson);
            toolCallsArray.add(tcJson);

            if ("web_search".equals(stc.name)) {
                try {
                    JSONObject args = JSON.getObject(stc.arguments.toString());
                    String q = args.getString("query");
                    queriesToSearch.add(q);
                    callIds.add(stc.id);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        assistantMsg.setToolCalls(toolCallsArray);
        assistantMsg.setSearchResultCount(0); // initial status "0 results"

        if (queriesToSearch.isEmpty()) {
            resetUIState();
            return;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                SearchEngine engine = SearchManager.getInstance().getEngine(MainActivity.this);
                int totalResults = 0;

                for (int i = 0; i < queriesToSearch.size(); i++) {
                    String q = queriesToSearch.get(i);
                    String callId = callIds.get(i);
                    String resultText;
                    try {
                        List<SearchResult> results = engine.search(q);
                        JSONObject responseJson = new JSONObject();
                        responseJson.put("query", q);
                        if (results == null || results.isEmpty()) {
                            Log.d("SearchEngine", "No results");
                            responseJson.put("status", "no_results");
                            responseJson.put("results", new JSONArray());
                        } else {
                            totalResults += results.size();
                            Log.d("SearchEngine", Integer.toString(results.size()));
                            Log.d("SearchEngine", results.toString());
                            responseJson.put("status", "success");
                            JSONArray resultsArray = new JSONArray();
                            int max = Math.min(results.size(), config.getConfig().getMaxSearchResults());
                            for (int k = 0; k < max; k++) {
                                SearchResult res = results.get(k);
                                JSONObject item = new JSONObject();
                                item.put("title", res.getTitle());
                                item.put("url", res.getUrl());
                                item.put("snippet", res.getSnippet());
                                resultsArray.add(item);
                            }
                            responseJson.put("results", resultsArray);
                        }
                        resultText = responseJson.toString();
                    } catch (Exception e) {
                        e.printStackTrace();
                        resultText = "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
                    }

                    Message toolMessage = new Message(Role.TOOL, resultText);
                    toolMessage.setToolCallId(callId);
                    MessageManager.getInstance().addMessage(toolMessage);
                }

                assistantMsg.setSearchResultCount(totalResults);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged();
                        if (!isCancelled) {
                            requestAICompletion();
                        } else {
                            resetUIState();
                        }
                    }
                });
            }
        }).start();
    }

    private void processContentWithTags(String token) {
        int cursor = 0;
        while (cursor < token.length()) {
            if (isThinkingState) {
                int endTag = token.indexOf("</think>", cursor);
                if (endTag != -1) {
                    thinkBuffer.append(token.substring(cursor, endTag));
                    isThinkingState = false;
                    cursor = endTag + 8;
                } else {
                    thinkBuffer.append(token.substring(cursor));
                    break;
                }
            } else {
                int startTag = token.indexOf("<think>", cursor);
                if (startTag != -1) {
                    contentBuffer.append(token.substring(cursor, startTag));
                    isThinkingState = true;
                    cursor = startTag + 7;
                } else {
                    contentBuffer.append(token.substring(cursor));
                    break;
                }
            }
        }
    }

    private void updateStreamUI(final Message msg, final boolean thinkingEnabled, final boolean isFinal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(new Runnable() {
                public void run() {
                    updateStreamUI(msg, thinkingEnabled, isFinal);
                }
            });
            return;
        }
        String displayContent = cleanChannelTokens(contentBuffer.toString());
        String displayThink = thinkBuffer.toString();

        if (thinkingEnabled && displayThink.length() > 0) {
            msg.setContent("<think>" + displayThink + "</think>" + displayContent);
        } else {
            msg.setContent(displayContent);
        }

        int firstVis = msgList.getFirstVisiblePosition();
        int lastVis = msgList.getLastVisiblePosition();
        int count = adapter.getCount();
        int targetIndex = count - 1;
        if (targetIndex >= firstVis && targetIndex <= lastVis) {
            View view = msgList.getChildAt(targetIndex - firstVis);
            if (view != null) {
                TextView tvText = (TextView) view.findViewById(R.id.message_text);
                LinearLayout thinkLayout = (LinearLayout) view.findViewById(R.id.thinkingLayout);
                TextView tvThink = (TextView) view.findViewById(R.id.thinkingProcess);
                View vResponse = view.findViewById(R.id.response);

                if (tvText != null) {
                    tvText.setMovementMethod(LinkMovementMethod.getInstance());
                    tvText.setText(MarkdownParser.parse(displayContent));
                }

                if (thinkingEnabled) {
                    boolean hasThinkContent = displayThink.length() > 0;
                    if (hasThinkContent) {
                        thinkLayout.setVisibility(View.VISIBLE);
                        View noThink = view.findViewById(R.id.noThinking);
                        if (noThink != null) noThink.setVisibility(View.GONE);
                        if (tvThink != null) {
                            tvThink.setMovementMethod(LinkMovementMethod.getInstance());
                            tvThink.setText(MarkdownParser.parse(displayThink));
                        }
                    } else {
                        thinkLayout.setVisibility(View.VISIBLE);
                        View noThink = view.findViewById(R.id.noThinking);
                        if (noThink != null) noThink.setVisibility(View.VISIBLE);
                    }
                } else {
                    if (thinkLayout != null) thinkLayout.setVisibility(View.GONE);
                }

                if (vResponse != null) {
                    vResponse.setVisibility((displayContent.length() > 0) ? View.VISIBLE : View.GONE);
                }
            }
        }

        if (autoScroll) scrollToBottom();
    }

    private static String cleanChannelTokens(String text) {
        if (text == null || text.length() == 0) return "";
        if (text.indexOf('<') == -1 && text.indexOf('t') == -1 && text.indexOf('T') == -1) {
            return text;
        }
        String cleaned = text.trim();
        if (cleaned.regionMatches(true, 0, "thought", 0, 7)) {
            cleaned = cleaned.substring(7).trim();
        }
        if (cleaned.startsWith("<|channel|>")) {
            cleaned = cleaned.substring(11).trim();
        } else if (cleaned.startsWith("<channel>")) {
            cleaned = cleaned.substring(9).trim();
        }
        return cleaned;
    }

    public static Bitmap decodeSampledBitmap(Context ctx, Uri uri, int reqW, int reqH) throws IOException {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, options);
        is.close();

        options.inSampleSize = calculateInSampleSize(options, reqW, reqH);
        options.inJustDecodeBounds = false;

        is = ctx.getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(is, null, options);
        is.close();
        return bmp;
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqW, int reqH) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqH || width > reqW) {
            if (width > height) {
                while ((width / inSampleSize) > reqW) {
                    inSampleSize *= 2;
                }
            } else {
                while ((height / inSampleSize) > reqH) {
                    inSampleSize *= 2;
                }
            }
        }
        return inSampleSize;
    }

    private String extractJSONReasoning(JSONObject delta) {
        try {
            return delta.getString("reasoning");
        } catch (Exception e) {
            try {
                return delta.getArray("reasoning_content").getObject(0).getString("thinking");
            } catch(Exception _) {
                return null;
            }
        }
    }
}
