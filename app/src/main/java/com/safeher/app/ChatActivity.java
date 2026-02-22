package com.safeher.app;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    // Optional: paste your OpenAI key here to enable GPT responses
    // Leave blank to use offline smart replies (recommended)
    private static final String OPENAI_KEY = "";

    private final List<ChatMessage> messages = new ArrayList<>();
    private ChatAdapter adapter;
    private EditText etMessage;
    private RecyclerView rvMessages;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ── BUILT-IN KNOWLEDGE BASE (works 100% offline) ──────────
    // Format: "keyword1|keyword2|keyword3" -> "response text"
    private static final LinkedHashMap<String, String> KB = new LinkedHashMap<>();

    static {
        KB.put("follow|stalk|stalker|following me",
            "If someone is following you:\n\n" +
            "1\uFE0F\u20E3 Move to a crowded, well-lit place NOW\n" +
            "2\uFE0F\u20E3 Enter any open shop or restaurant\n" +
            "3\uFE0F\u20E3 Call someone — stay on the phone\n" +
            "4\uFE0F\u20E3 Do NOT go home directly\n" +
            "5\uFE0F\u20E3 Call Police: 100 or Women Helpline: 1091\n\n" +
            "Trust your instincts. Your safety matters. \uD83D\uDC99");

        KB.put("fir|complaint|report|lodge",
            "How to file an FIR:\n\n" +
            "1\uFE0F\u20E3 Go to ANY nearby police station\n" +
            "2\uFE0F\u20E3 Ask for the Officer-in-Charge\n" +
            "3\uFE0F\u20E3 Give your statement — they must write it\n" +
            "4\uFE0F\u20E3 Demand a FREE copy of your FIR (your right)\n" +
            "5\uFE0F\u20E3 If refused, call: 1091 or file online at your state police website\n\n" +
            "\u2696\uFE0F Under CrPC Section 154, police CANNOT refuse to register an FIR.");

        KB.put("workplace|office|posh|boss|colleague|coworker|work harassment",
            "Workplace Harassment — POSH Act 2013:\n\n" +
            "\u2705 Every company (10+ employees) MUST have an Internal Complaints Committee (ICC)\n" +
            "\u2705 File complaint within 3 months of incident\n" +
            "\u2705 Your identity stays confidential\n" +
            "\u2705 You CANNOT be fired for complaining\n\n" +
            "Steps:\n" +
            "1\uFE0F\u20E3 Write complaint to your ICC chairperson\n" +
            "2\uFE0F\u20E3 If no ICC exists, report to District Officer\n" +
            "3\uFE0F\u20E3 Call: 181 (Women Helpline, most states)");

        KB.put("domestic|husband|spouse|family violence|home violence|beating|abuse",
            "Domestic Violence — Your Rights:\n\n" +
            "\uD83D\uDEE1\uFE0F Protection of Women from Domestic Violence Act 2005 protects you\n\n" +
            "You can get:\n" +
            "• Protection Order\n" +
            "• Right to stay in your own home\n" +
            "• Monetary relief\n" +
            "• Child custody\n\n" +
            "Call NOW:\n" +
            "\uD83D\uDCDE Women Helpline: 1091\n" +
            "\uD83D\uDCDE NCW Helpline: 7827170170\n" +
            "\uD83D\uDCDE Police: 100");

        KB.put("rape|assault|molest|sexual|touched me",
            "If you have been assaulted:\n\n" +
            "1\uFE0F\u20E3 Get to safety first — hospital or police station\n" +
            "2\uFE0F\u20E3 Do NOT shower or change clothes (evidence)\n" +
            "3\uFE0F\u20E3 Call: 1091 — they will guide you step by step\n" +
            "4\uFE0F\u20E3 Report at ANY police station, not just local\n" +
            "5\uFE0F\u20E3 A female officer MUST be present for your statement\n" +
            "6\uFE0F\u20E3 Medical exam is FREE at government hospitals\n\n" +
            "You are NOT alone. This is NOT your fault. \uD83D\uDC99");

        KB.put("right|law|legal|ipc|section|act|constitution",
            "Key Legal Rights for Women in India:\n\n" +
            "\u2696\uFE0F IPC 354 — Assault / outraging modesty\n" +
            "\u2696\uFE0F IPC 354A — Sexual harassment\n" +
            "\u2696\uFE0F IPC 354D — Stalking (up to 5 years prison)\n" +
            "\u2696\uFE0F IPC 375/376 — Rape (up to life imprisonment)\n" +
            "\u2696\uFE0F IPC 498A — Cruelty by husband/family\n" +
            "\u2696\uFE0F POSH Act 2013 — Workplace harassment\n\n" +
            "\uD83D\uDCDE Free Legal Aid: 15100");

        KB.put("safe|night|alone|travel|walk|cab|auto|uber|ola|taxi",
            "Safety Tips for Travelling Alone:\n\n" +
            "\uD83C\uDF19 At night:\n" +
            "• Share live location with trusted person\n" +
            "• Stay on well-lit, busy roads\n" +
            "• Keep phone charged, contacts ready\n" +
            "• Use SaveSouls SOS if unsafe\n\n" +
            "\uD83D\uDE95 In cabs/autos:\n" +
            "• Share trip details with family\n" +
            "• Note the vehicle number\n" +
            "• Sit behind driver, not next to them\n" +
            "• If uncomfortable, trust your gut — get out");

        KB.put("cyber|online|internet|photo|blackmail|threat|whatsapp message|nude",
            "Cyber Crime Against Women:\n\n" +
            "1\uFE0F\u20E3 Screenshot everything as evidence first\n" +
            "2\uFE0F\u20E3 Report at: cybercrime.gov.in\n" +
            "3\uFE0F\u20E3 Call Cyber Crime Helpline: 1930\n" +
            "4\uFE0F\u20E3 Block the person on all platforms\n" +
            "5\uFE0F\u20E3 Do NOT pay money — it will not stop\n\n" +
            "\u2696\uFE0F IT Act 66E & IPC 354C protect you.\n" +
            "Sharing intimate images without consent = CRIME.");

        KB.put("mental|stress|scared|afraid|anxious|depressed|sad|help me|suicid|hopeless",
            "I hear you, and I'm here for you. \uD83D\uDC99\n\n" +
            "What you're feeling is valid. You are not alone.\n\n" +
            "Please reach out:\n\n" +
            "\uD83D\uDCDE iCall (TISS): 9152987821\n" +
            "\uD83D\uDCDE Vandrevala Foundation: 1860-2662-345 (24\u00D77 Free)\n" +
            "\uD83D\uDCDE Women Helpline: 1091\n\n" +
            "If you are in immediate danger, call 100. You deserve to be safe. \uD83E\uDEF6");

        KB.put("helpline|number|call|contact|emergency number",
            "\uD83D\uDCDE Emergency Helplines — India:\n\n" +
            "\uD83D\uDC6E Women Helpline: 1091\n" +
            "\uD83D\uDE94 Police: 100\n" +
            "\uD83C\uDFE5 Ambulance: 108\n" +
            "\uD83D\uDCDE CHILDLINE: 1098\n" +
            "\u2696\uFE0F Legal Aid: 15100\n" +
            "\uD83D\uDC99 Mental Health (Vandrevala): 1860-2662-345\n" +
            "\uD83D\uDCDE NCW: 7827170170\n" +
            "\uD83D\uDCBB Cyber Crime: 1930\n\n" +
            "All calls are free and confidential.");

        KB.put("dowry|marriage|divorce|husband family",
            "Dowry & Marriage Rights:\n\n" +
            "\u2696\uFE0F Dowry Prohibition Act 1961 — Demanding dowry is a crime\n" +
            "\u2696\uFE0F IPC 498A — Cruelty by husband/in-laws is punishable\n" +
            "\u2696\uFE0F IPC 304B — Dowry death (imprisonment for life)\n\n" +
            "You have the right to:\n" +
            "• Live without harassment\n" +
            "• Keep your streedhan (jewelry, gifts)\n" +
            "• File for divorce and seek maintenance\n\n" +
            "\uD83D\uDCDE NCW Helpline: 7827170170");
    }

    private static final String FALLBACK =
        "I'm here to help with women's safety. You can ask me about:\n\n" +
        "\uD83D\uDEA8 Being followed or stalked\n" +
        "\uD83D\uDCCB How to file a police FIR\n" +
        "\uD83D\uDCBC Workplace harassment (POSH Act)\n" +
        "\uD83C\uDFE0 Domestic violence rights\n" +
        "\uD83D\uDCDE Emergency helplines\n" +
        "\uD83C\uDF19 Travelling safely alone\n" +
        "\uD83D\uDCBB Cyber crime help\n" +
        "\u2696\uFE0F Your legal rights\n\n" +
        "What would you like to know? \uD83D\uDC99";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        findViewById(R.id.tvBack).setOnClickListener(v -> finish());

        rvMessages = findViewById(R.id.rvMessages);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        rvMessages.setLayoutManager(llm);
        adapter = new ChatAdapter(messages);
        rvMessages.setAdapter(adapter);

        etMessage = findViewById(R.id.etMessage);
        ((MaterialButton) findViewById(R.id.btnSend)).setOnClickListener(v -> sendMessage());

        setupQuickPrompts();
        addBotMessage(
            "Hello! I'm SafeHer AI \uD83D\uDC99\n\n" +
            "I can help with safety guidance, legal rights & emergency numbers. " +
            "Tap a quick option or type your question.\n\n" +
            "In immediate danger? Call \uD83D\uDCDE 1091 (Women Helpline) or use SOS."
        );
    }

    private void setupQuickPrompts() {
        LinearLayout chipGroup = findViewById(R.id.chipGroup);
        String[][] chips = {
            {"\uD83D\uDEA8 Being followed",    "Someone is following me"},
            {"\uD83D\uDCCB File FIR",           "How to file a police FIR"},
            {"\uD83D\uDCBC Workplace",           "Workplace harassment POSH Act"},
            {"\uD83C\uDFE0 Home violence",       "Domestic violence husband"},
            {"\uD83D\uDCDE Helplines",           "Emergency helpline numbers"},
            {"\uD83C\uDF19 Night safety",        "Safety tips travelling alone at night"},
        };
        for (String[] chip : chips) {
            TextView btn = new TextView(this);
            btn.setText(chip[0]);
            btn.setTextSize(12f);
            btn.setTextColor(Color.parseColor("#FF2D55"));
            btn.setBackground(getDrawable(R.drawable.chip_bg));
            btn.setPadding(28, 16, 28, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 12, 0);
            btn.setLayoutParams(lp);
            final String query = chip[1];
            btn.setOnClickListener(v -> { etMessage.setText(query); sendMessage(); });
            chipGroup.addView(btn);
        }
    }

    private void sendMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText("");
        addUserMessage(text);
        addBotMessage("...");
        handler.postDelayed(() -> replaceLastBotMessage(getReply(text)), 600);
    }

    private String getReply(String input) {
        String lower = input.toLowerCase();

        // Greeting
        if (lower.matches(".*(\\bhi\\b|hello|hey|namaste|hii|helo|good morning|good evening).*")) {
            return "Hello! \uD83D\uDC4B I'm SafeHer AI. I'm here to help keep you safe.\n\n" +
                "Ask me about safety, legal rights, or emergency helplines. What's on your mind?";
        }

        // Thank you
        if (lower.matches(".*(thank|thanks|shukriya|dhanyawad).*")) {
            return "You're welcome! Stay safe. \uD83D\uDEE1\uFE0F\n\nIf you need anything else, I'm here anytime.";
        }

        // Search knowledge base
        for (Map.Entry<String, String> entry : KB.entrySet()) {
            String[] keywords = entry.getKey().split("\\|");
            for (String kw : keywords) {
                if (lower.contains(kw.trim())) {
                    return entry.getValue();
                }
            }
        }

        return FALLBACK;
    }

    private void addUserMessage(String t) {
        messages.add(new ChatMessage(t, true));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void addBotMessage(String t) {
        messages.add(new ChatMessage(t, false));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void replaceLastBotMessage(String t) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!messages.get(i).isUser) {
                messages.get(i).text = t;
                adapter.notifyItemChanged(i);
                rvMessages.scrollToPosition(i);
                return;
            }
        }
    }

    static class ChatMessage {
        String text; boolean isUser;
        ChatMessage(String t, boolean u) { text = t; isUser = u; }
    }

    static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private final List<ChatMessage> list;
        ChatAdapter(List<ChatMessage> l) { list = l; }

        class VH extends RecyclerView.ViewHolder {
            TextView tvMsg; View bubble;
            VH(View v) { super(v); tvMsg = v.findViewById(R.id.tvMessage); bubble = v.findViewById(R.id.bubble); }
        }

        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_message, p, false));
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            ChatMessage m = list.get(pos);
            h.tvMsg.setText(m.text);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubble.getLayoutParams();
            if (m.isUser) {
                lp.gravity = Gravity.END;
                h.bubble.setBackgroundResource(R.drawable.bubble_user);
                h.tvMsg.setTextColor(Color.WHITE);
            } else {
                lp.gravity = Gravity.START;
                h.bubble.setBackgroundResource(R.drawable.bubble_bot);
                h.tvMsg.setTextColor(Color.parseColor("#F0EEF8"));
            }
            h.bubble.setLayoutParams(lp);
        }

        @Override public int getItemCount() { return list.size(); }
    }
}
