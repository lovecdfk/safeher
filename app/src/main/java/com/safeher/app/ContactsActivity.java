package com.safeher.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ContactsActivity extends AppCompatActivity {

    private final List<JSONObject> contacts = new ArrayList<>();
    private ContactAdapter adapter;
    private EditText etName, etPhone;  // plain EditText, matches layout XML

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        findViewById(R.id.tvBack).setOnClickListener(v -> finish());

        etName  = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);

        MaterialButton btnAdd = findViewById(R.id.btnAddContact);
        btnAdd.setOnClickListener(v -> addContact());

        RecyclerView rv = findViewById(R.id.rvContacts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactAdapter();
        rv.setAdapter(adapter);

        loadContacts();
    }

    private void addContact() {
        String name  = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phone.isEmpty()) {
            Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject c = new JSONObject();
            c.put("name", name);
            c.put("phone", phone);
            contacts.add(c);
            saveContacts();
            adapter.notifyItemInserted(contacts.size() - 1);
            etName.setText("");
            etPhone.setText("");
            Toast.makeText(this, "Added: " + name, Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, "Error saving contact", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeContact(int pos) {
        if (pos < 0 || pos >= contacts.size()) return;
        String name = "";
        try { name = contacts.get(pos).getString("name"); } catch (JSONException ignored) {}
        contacts.remove(pos);
        saveContacts();
        adapter.notifyItemRemoved(pos);
        adapter.notifyItemRangeChanged(pos, contacts.size());
        Toast.makeText(this, name + " removed", Toast.LENGTH_SHORT).show();
    }

    private void loadContacts() {
        try {
            String json = getSharedPreferences("SaveSouls", MODE_PRIVATE)
                .getString("contacts", "[]");
            contacts.clear();
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) contacts.add(arr.getJSONObject(i));
        } catch (JSONException e) {
            contacts.clear();
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void saveContacts() {
        JSONArray arr = new JSONArray(contacts);
        getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .edit().putString("contacts", arr.toString()).apply();
    }

    class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView tvAvatar, tvName, tvPhone;
            MaterialButton btnDelete;

            VH(View v) {
                super(v);
                tvAvatar  = v.findViewById(R.id.tvAvatar);
                tvName    = v.findViewById(R.id.tvContactName);
                tvPhone   = v.findViewById(R.id.tvContactPhone);
                btnDelete = v.findViewById(R.id.btnDeleteContact);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            try {
                JSONObject c = contacts.get(position);
                String name  = c.getString("name");
                String phone = c.getString("phone");
                holder.tvName.setText(name);
                holder.tvPhone.setText(phone);
                holder.tvAvatar.setText(name.length() > 0
                    ? String.valueOf(name.charAt(0)).toUpperCase() : "?");

                // getBindingAdapterPosition() is the non-deprecated way, prevents crash
                holder.btnDelete.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) removeContact(pos);
                });
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public int getItemCount() { return contacts.size(); }
    }
}
