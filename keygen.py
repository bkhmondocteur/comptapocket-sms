import tkinter as tk
from tkinter import messagebox
import hashlib

SECRET_SALT_COMPTA = "COMPTA_SEC_2026"
SECRET_SALT_MEDWIN = "MEDWIN_SEC_2026"

def generate_key(name: str, salt: str = SECRET_SALT_COMPTA) -> str:
    name_clean = name.strip().upper()
    if not name_clean:
        return ""
    
    raw_string = name_clean + salt
    md5_hash = hashlib.md5(raw_string.encode('utf-8')).hexdigest().upper()
    
    key_base = md5_hash[:16]
    formatted_key = f"{key_base[0:4]}-{key_base[4:8]}-{key_base[8:12]}-{key_base[12:16]}"
    return formatted_key

def on_generate():
    name = entry_name.get().strip()
    if not name:
        messagebox.showerror("Erreur", "Veuillez entrer le nom du cabinet comptable.")
        return
    
    key = generate_key(name, SECRET_SALT_COMPTA)
    entry_key.delete(0, tk.END)
    entry_key.insert(0, key)

# UI Setup
root = tk.Tk()
root.title("ComptaPocket SMS - Générateur de Clé de Licence")
root.geometry("460x280")
root.configure(padx=24, pady=24, bg="#0d1b2a")

tk.Label(root, text="ComptaPocket SMS", font=("Arial", 18, "bold"), fg="#e0e1dd", bg="#0d1b2a").pack(pady=5)
tk.Label(root, text="Générateur de Licence Cabinet", font=("Arial", 11), fg="#778da9", bg="#0d1b2a").pack(pady=2)

tk.Label(root, text="Nom du Bureau Comptable / Expert :", font=("Arial", 10, "bold"), fg="#e0e1dd", bg="#0d1b2a").pack(pady=(12, 4))
entry_name = tk.Entry(root, width=38, font=("Arial", 12), justify="center")
entry_name.pack(pady=4)

btn = tk.Button(root, text="Générer la Clé", command=on_generate, bg="#1b4965", fg="white", font=("Arial", 11, "bold"), padx=12, pady=6)
btn.pack(pady=10)

entry_key = tk.Entry(root, width=36, font=("Courier", 14, "bold"), justify="center", bg="#f8f9fa")
entry_key.pack(pady=4)

root.mainloop()
