/**
 * ComptaPocket SMS - Progressive Web App (PWA)
 * Gestion des échéances fiscales, cotisations sociales et base de données clients
 */

const DEFAULT_CONFIG = {
  cabinetName: "Bureau de Comptabilité",
  cabinetPhone: "",
  autoSend: true,
  dueDayEntreprise: 28,
  dueDayPhysique: 15,
  cnssDueDay: 15,
  acomptesDayEntreprise: 28,
  acomptesDayPhysique: 25,
  defaultDaysBefore: 3,
  defaultSendTime: "09:00",
  templates: {
    mensuelle: "Bonjour [Nom], votre bureau comptable [Cabinet] vous rappelle que la date limite de votre déclaration mensuelle est fixée au [Date_Echeance]. Merci de nous faire parvenir vos documents.",
    cnss: "Bonjour [Nom], rappel de votre bureau comptable [Cabinet] : la date limite de paiement des cotisations CNSS est fixée au [Date_Echeance]. Merci de régler dans les délais pour éviter toute pénalité.",
    acomptes: "Bonjour [Nom], votre bureau comptable [Cabinet] vous informe que l'échéance de l'acompte provisionnel est le [Date_Echeance]. Merci de nous contacter pour validation et dépôt."
  }
};

let config = loadConfig();
let clients = loadClients(); // Liste des rappels d'échéances
let clientDatabase = loadClientDatabase(); // Base de données des clients

let activeMainTab = 'reminders'; // 'reminders' ou 'database'
let activeCategoryFilter = 'ALL';
let activeTypeFilter = 'ALL';
let searchQuery = '';
let dbCategoryFilter = 'ALL';
let dbSearchQuery = '';
let batchMode = 'EXACT';
let simulatedDate = localStorage.getItem('comptapocket_simulated_date') || null;

function getTodayDate() {
  if (simulatedDate) {
    const parts = simulatedDate.split('-');
    return new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]), 9, 0, 0);
  }
  return new Date();
}

// --- Initialisation au chargement ---
document.addEventListener('DOMContentLoaded', () => {
  initFormDates();
  applyConfigUI();
  updateSmsPreview();
  renderTable();
  renderDatabaseTable();
  updateStats();
  updateSimulationUI();
  registerSW();

  // Recherche dans les rappels
  const searchInput = document.getElementById('search-input');
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      searchQuery = e.target.value.trim().toLowerCase();
      renderTable();
    });
  }

  // Recherche dans la base de données clients
  const dbSearchInput = document.getElementById('db-search-input');
  if (dbSearchInput) {
    dbSearchInput.addEventListener('input', (e) => {
      dbSearchQuery = e.target.value.trim().toLowerCase();
      renderDatabaseTable();
    });
  }

  const clientNameInput = document.getElementById('client-name');
  if (clientNameInput) {
    clientNameInput.addEventListener('input', () => {
      updateSmsPreview();
    });
  }

  const smsTextInput = document.getElementById('sms-text');
  if (smsTextInput) {
    smsTextInput.addEventListener('input', (e) => {
      document.getElementById('sms-char-count').innerText = `${e.target.value.length} caractères`;
    });
  }

  document.getElementById('reminder-form').addEventListener('submit', onSaveClient);
  document.getElementById('btn-reset-form').addEventListener('click', resetForm);
  document.getElementById('btn-batch-modal').addEventListener('click', openBatchModal);
  document.getElementById('btn-send-all').addEventListener('click', onSendAllPending);
  document.getElementById('btn-open-settings').addEventListener('click', openSettingsModal);
  const btnOpenAbout = document.getElementById('btn-open-about');
  if (btnOpenAbout) {
    btnOpenAbout.addEventListener('click', openAboutModal);
  }
  const btnOpenSim = document.getElementById('btn-open-simulation');
  if (btnOpenSim) {
    btnOpenSim.addEventListener('click', openSimulationModal);
  }
});

// --- Gestion des Onglets Principaux ---
function switchMainTab(tab) {
  activeMainTab = tab;
  const viewReminders = document.getElementById('view-reminders');
  const viewDatabase = document.getElementById('view-database');
  const tabBtnReminders = document.getElementById('tab-btn-reminders');
  const tabBtnDatabase = document.getElementById('tab-btn-database');

  if (tab === 'reminders') {
    viewReminders.style.display = 'block';
    viewDatabase.style.display = 'none';
    tabBtnReminders.classList.add('active');
    tabBtnDatabase.classList.remove('active');
    renderTable();
  } else {
    viewReminders.style.display = 'none';
    viewDatabase.style.display = 'block';
    tabBtnReminders.classList.remove('active');
    tabBtnDatabase.classList.add('active');
    renderDatabaseTable();
  }
}

// --- Fonctions de Stockage Local ---
function loadConfig() {
  try {
    const raw = localStorage.getItem('comptapocket_config');
    return raw ? Object.assign({}, DEFAULT_CONFIG, JSON.parse(raw)) : DEFAULT_CONFIG;
  } catch (e) {
    return DEFAULT_CONFIG;
  }
}

function saveConfig(cfg) {
  config = cfg;
  localStorage.setItem('comptapocket_config', JSON.stringify(cfg));
  applyConfigUI();
}

function loadClients() {
  try {
    const raw = localStorage.getItem('comptapocket_clients');
    if (raw) return JSON.parse(raw);
  } catch (e) {}

  return [
    {
      id: 1,
      clientName: "Société Maghreb Distribution",
      category: "ENTREPRISE",
      telephone: "98123456",
      email: "contact@maghreb-dist.com",
      taxId: "1234567/A/M/000",
      reminderType: "DECLARATION_MENSUELLE",
      dueDate: computeDefaultDueDate("ENTREPRISE", "DECLARATION_MENSUELLE"),
      sendDate: computeDefaultSendDate(computeDefaultDueDate("ENTREPRISE", "DECLARATION_MENSUELLE"), 3),
      sendTime: "09:00",
      texteSms: "Bonjour Société Maghreb Distribution, votre bureau comptable Bureau de Comptabilité vous rappelle que la date limite de votre déclaration mensuelle est fixée au 28 du mois. Merci de nous faire parvenir vos documents.",
      smsEnvoye: false
    },
    {
      id: 2,
      clientName: "Dr. Karim Mansour (Cabinet Médical)",
      category: "PERSONNE_PHYSIQUE",
      telephone: "97654321",
      email: "dr.mansour@gmail.com",
      taxId: "0987654/B/P/000",
      reminderType: "DECLARATION_MENSUELLE",
      dueDate: computeDefaultDueDate("PERSONNE_PHYSIQUE", "DECLARATION_MENSUELLE"),
      sendDate: computeDefaultSendDate(computeDefaultDueDate("PERSONNE_PHYSIQUE", "DECLARATION_MENSUELLE"), 3),
      sendTime: "09:00",
      texteSms: "Bonjour Dr. Karim Mansour, votre bureau comptable Bureau de Comptabilité vous rappelle que la date limite de votre déclaration mensuelle est fixée au 15 du mois. Merci de nous faire parvenir vos documents.",
      smsEnvoye: false
    }
  ];
}

function saveClients() {
  localStorage.setItem('comptapocket_clients', JSON.stringify(clients));
  syncClientsToDatabase();
  renderTable();
  renderDatabaseTable();
  updateStats();
}

// --- Base de données Clients ---
function loadClientDatabase() {
  try {
    const raw = localStorage.getItem('comptapocket_db_clients');
    if (raw) return JSON.parse(raw);
  } catch (e) {}

  // Population initiale basée sur les clients existants
  const initial = [];
  const existingReminders = loadClients();
  existingReminders.forEach(r => {
    if (!initial.some(c => c.telephone === r.telephone || c.clientName === r.clientName)) {
      initial.push({
        id: Date.now() + Math.floor(Math.random() * 10000),
        clientName: r.clientName,
        category: r.category,
        telephone: r.telephone,
        email: r.email || '',
        taxId: r.taxId || '',
        address: '',
        notes: ''
      });
    }
  });
  return initial;
}

function saveClientDatabase() {
  localStorage.setItem('comptapocket_db_clients', JSON.stringify(clientDatabase));
  renderDatabaseTable();
  updateStats();
}

function syncClientsToDatabase() {
  let changed = false;
  clients.forEach(r => {
    const exists = clientDatabase.some(c => c.telephone === r.telephone || c.clientName === r.clientName);
    if (!exists) {
      clientDatabase.push({
        id: Date.now() + Math.floor(Math.random() * 10000),
        clientName: r.clientName,
        category: r.category,
        telephone: r.telephone,
        email: r.email || '',
        taxId: r.taxId || '',
        address: '',
        notes: ''
      });
      changed = true;
    }
  });
  if (changed) {
    localStorage.setItem('comptapocket_db_clients', JSON.stringify(clientDatabase));
  }
}

// --- Calculs de Dates & Automatisations ---
function computeInitialSchedule(category, type) {
  const d = getTodayDate();
  const year = d.getFullYear();
  const month = d.getMonth();
  const todayStr = formatDate(d);
  const nowTime = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const defaultTime = config.defaultSendTime || '09:00';
  const daysBefore = config.defaultDaysBefore || 3;

  if (type === 'DECLARATION_MENSUELLE') {
    const dueDay = (category === 'ENTREPRISE') ? (config.dueDayEntreprise || 28) : (config.dueDayPhysique || 15);
    const currentDay = d.getDate();

    if (currentDay <= dueDay) {
      // Ajouté entre le 1er et le 15 (ou 28) : envoi IMMÉDIAT
      const target = new Date(year, month, dueDay);
      return {
        dueDate: formatDate(target),
        sendDate: todayStr,
        sendTime: nowTime,
        shouldSendImmediately: true
      };
    } else {
      // Dépassé pour ce mois : programmé pour le mois prochain
      const target = new Date(year, month + 1, dueDay);
      const plannedSend = new Date(target);
      plannedSend.setDate(plannedSend.getDate() - daysBefore);
      return {
        dueDate: formatDate(target),
        sendDate: formatDate(plannedSend),
        sendTime: defaultTime,
        shouldSendImmediately: false
      };
    }
  } else if (type === 'CNSS') {
    const cnssDay = config.cnssDueDay || 15;
    const quarters = [new Date(year, 0, cnssDay), new Date(year, 3, cnssDay), new Date(year, 6, cnssDay), new Date(year, 9, cnssDay), new Date(year + 1, 0, cnssDay)];

    let target = null;
    let sendNow = false;

    for (const q of quarters) {
      const windowStart = new Date(q);
      windowStart.setDate(windowStart.getDate() - daysBefore);
      if (d >= windowStart && d <= q) {
        target = q;
        sendNow = true;
        break;
      } else if (d < windowStart) {
        target = q;
        sendNow = false;
        break;
      }
    }
    if (!target) target = quarters[quarters.length - 1];

    if (sendNow) {
      return {
        dueDate: formatDate(target),
        sendDate: todayStr,
        sendTime: nowTime,
        shouldSendImmediately: true
      };
    } else {
      const plannedSend = new Date(target);
      plannedSend.setDate(plannedSend.getDate() - daysBefore);
      return {
        dueDate: formatDate(target),
        sendDate: formatDate(plannedSend),
        sendTime: defaultTime,
        shouldSendImmediately: false
      };
    }
  } else if (type === 'ACOMPTES_PROVISIONNELS') {
    const day = (category === 'ENTREPRISE') ? (config.acomptesDayEntreprise || 28) : (config.acomptesDayPhysique || 25);
    const acomptes = [new Date(year, 5, day), new Date(year, 8, day), new Date(year, 11, day), new Date(year + 1, 5, day)];

    let target = null;
    let sendNow = false;

    for (const ac of acomptes) {
      const windowStart = new Date(ac);
      windowStart.setDate(windowStart.getDate() - daysBefore);
      if (d >= windowStart && d <= ac) {
        target = ac;
        sendNow = true;
        break;
      } else if (d < windowStart) {
        target = ac;
        sendNow = false;
        break;
      }
    }
    if (!target) target = acomptes[acomptes.length - 1];

    if (sendNow) {
      return {
        dueDate: formatDate(target),
        sendDate: todayStr,
        sendTime: nowTime,
        shouldSendImmediately: true
      };
    } else {
      const plannedSend = new Date(target);
      plannedSend.setDate(plannedSend.getDate() - daysBefore);
      return {
        dueDate: formatDate(target),
        sendDate: formatDate(plannedSend),
        sendTime: defaultTime,
        shouldSendImmediately: false
      };
    }
  } else {
    const target = new Date(year, month, 15);
    return {
      dueDate: formatDate(target),
      sendDate: todayStr,
      sendTime: defaultTime,
      shouldSendImmediately: false
    };
  }
}

function computeDefaultDueDate(category, type) {
  const schedule = computeInitialSchedule(category, type);
  return schedule.dueDate;
}

function computeDefaultSendDate(dueStr, daysBefore = 3) {
  try {
    const parts = dueStr.split('-');
    const d = new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
    d.setDate(d.getDate() - daysBefore);
    return formatDate(d);
  } catch (e) {
    return dueStr;
  }
}

function formatDate(d) {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

// --- Gestion des formulaires ---
function selectCategory(cat) {
  document.getElementById('client-category').value = cat;
  const btnEnt = document.getElementById('btn-cat-entreprise');
  const btnPhy = document.getElementById('btn-cat-physique');
  const label = document.getElementById('label-client-name');

  if (cat === 'ENTREPRISE') {
    btnEnt.classList.add('active');
    btnPhy.classList.remove('active');
    label.innerText = "Raison Sociale / Nom Société * :";
  } else {
    btnPhy.classList.add('active');
    btnEnt.classList.remove('active');
    label.innerText = "Nom & Prénom (Personne Physique) * :";
  }

  initFormDates();
  updateSmsPreview();
}

function onTypeOrCategoryChanged() {
  initFormDates();
  updateSmsPreview();
}

function onDueDateChanged() {
  const due = document.getElementById('due-date').value;
  if (due) {
    document.getElementById('send-date').value = computeDefaultSendDate(due, config.defaultDaysBefore || 3);
    updateSmsPreview();
  }
}

function initFormDates() {
  const cat = document.getElementById('client-category').value;
  const type = document.getElementById('reminder-type').value;
  const effectiveType = type === 'PACK_COMPLET' ? 'DECLARATION_MENSUELLE' : type;
  const schedule = computeInitialSchedule(cat, effectiveType);

  document.getElementById('due-date').value = schedule.dueDate;
  document.getElementById('send-date').value = schedule.sendDate;
  document.getElementById('send-time').value = schedule.sendTime;
  document.getElementById('batch-date').value = schedule.dueDate;

  const summary = document.getElementById('auto-dates-summary');
  if (summary) {
    if (schedule.shouldSendImmediately) {
      summary.innerHTML = `⚡ <strong>Envoi Immédiat :</strong> Client ajouté dans la période active. Le premier SMS sera envoyé immédiatement pour ce mois, puis renouvelé périodiquement.`;
    } else {
      summary.innerHTML = `📅 <strong>Date limite :</strong> ${schedule.dueDate} | ⏰ <strong>Envoi programmé :</strong> ${schedule.sendDate} à ${schedule.sendTime}.`;
    }
  }
}

// --- Générateur de Texte SMS ---
function generateSms(name, cat, type, dueDate) {
  let template = config.templates.mensuelle;
  if (type === 'CNSS') template = config.templates.cnss;
  else if (type === 'ACOMPTES_PROVISIONNELS') template = config.templates.acomptes;

  const clientName = name || "[Nom du Client]";
  const cabinet = config.cabinetName || "Bureau de Comptabilité";

  let txt = template
    .replace(/\[Nom\]/gi, clientName)
    .replace(/\{Nom du Client\}/gi, clientName)
    .replace(/\[Cabinet\]/gi, cabinet)
    .replace(/\{Nom du Cabinet\}/gi, cabinet)
    .replace(/\[Date_Echeance\]/gi, dueDate)
    .replace(/\[Date\]/gi, dueDate);

  if (config.cabinetPhone && !txt.includes(config.cabinetPhone)) {
    txt += ` Contact: ${config.cabinetPhone}`;
  }
  return txt;
}

function updateSmsPreview() {
  const editId = document.getElementById('edit-id').value;
  if (editId) return;

  const name = document.getElementById('client-name').value.trim();
  const cat = document.getElementById('client-category').value;
  const type = document.getElementById('reminder-type').value;
  const effectiveType = type === 'PACK_COMPLET' ? 'DECLARATION_MENSUELLE' : type;
  const due = document.getElementById('due-date').value;

  const msg = generateSms(name, cat, effectiveType, due);
  document.getElementById('sms-text').value = msg;
  const countEl = document.getElementById('sms-char-count');
  if (countEl) countEl.innerText = `${msg.length} caractères`;
}

// --- Programmation automatique du cycle périodique suivant ---
function scheduleNextPeriodicReminder(currentClient) {
  const existing = clients.find(c =>
    c.clientName === currentClient.clientName &&
    c.telephone === currentClient.telephone &&
    c.reminderType === currentClient.reminderType &&
    !c.smsEnvoye
  );
  if (existing) return;

  const parts = currentClient.dueDate.split('-');
  const cal = new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
  const daysBefore = config.defaultDaysBefore || 3;
  const defaultTime = config.defaultSendTime || '09:00';

  let nextDue;
  if (currentClient.reminderType === 'DECLARATION_MENSUELLE') {
    const dueDay = (currentClient.category === 'ENTREPRISE') ? (config.dueDayEntreprise || 28) : (config.dueDayPhysique || 15);
    cal.setMonth(cal.getMonth() + 1);
    cal.setDate(dueDay);
    nextDue = formatDate(cal);
  } else if (currentClient.reminderType === 'CNSS') {
    const cnssDay = config.cnssDueDay || 15;
    cal.setMonth(cal.getMonth() + 3);
    cal.setDate(cnssDay);
    nextDue = formatDate(cal);
  } else if (currentClient.reminderType === 'ACOMPTES_PROVISIONNELS') {
    const day = (currentClient.category === 'ENTREPRISE') ? (config.acomptesDayEntreprise || 28) : (config.acomptesDayPhysique || 25);
    const m = cal.getMonth();
    if (m <= 5) cal.setMonth(8);
    else if (m <= 8) cal.setMonth(11);
    else {
      cal.setFullYear(cal.getFullYear() + 1);
      cal.setMonth(5);
    }
    cal.setDate(day);
    nextDue = formatDate(cal);
  } else {
    cal.setMonth(cal.getMonth() + 1);
    nextDue = formatDate(cal);
  }

  const nextSend = computeDefaultSendDate(nextDue, daysBefore);
  const nextSms = generateSms(currentClient.clientName, currentClient.category, currentClient.reminderType, nextDue);

  const nextClient = {
    id: Date.now() + Math.floor(Math.random() * 1000),
    clientName: currentClient.clientName,
    category: currentClient.category,
    telephone: currentClient.telephone,
    taxId: currentClient.taxId,
    email: currentClient.email,
    reminderType: currentClient.reminderType,
    dueDate: nextDue,
    sendDate: nextSend,
    sendTime: defaultTime,
    texteSms: nextSms,
    smsEnvoye: false
  };

  clients.unshift(nextClient);
  saveClients();
}

// --- Sauvegarde d'un rappel ---
function onSaveClient(e) {
  e.preventDefault();

  const id = document.getElementById('edit-id').value;
  const name = document.getElementById('client-name').value.trim();
  const cat = document.getElementById('client-category').value;
  const phone = document.getElementById('client-phone').value.trim();
  const taxId = document.getElementById('client-taxid').value.trim();
  const email = document.getElementById('client-email').value.trim();
  const type = document.getElementById('reminder-type').value;
  const dueDateInput = document.getElementById('due-date').value;
  const sendDateInput = document.getElementById('send-date').value;
  const sendTimeInput = document.getElementById('send-time').value;
  const texteSms = document.getElementById('sms-text').value.trim();

  if (!name || !phone) {
    alert("Veuillez renseigner au minimum le nom et le numéro mobile.");
    return;
  }

  if (id) {
    // Modification d'un rappel existant
    const idx = clients.findIndex(c => c.id == id);
    if (idx !== -1) {
      clients[idx] = Object.assign(clients[idx], {
        clientName: name,
        category: cat,
        telephone: phone,
        taxId: taxId,
        email: email,
        reminderType: type,
        dueDate: dueDateInput,
        sendDate: sendDateInput,
        sendTime: sendTimeInput || '09:00',
        texteSms: texteSms,
        smsEnvoye: false
      });
    }
  } else {
    // Nouveau client
    const typesToAdd = (type === 'PACK_COMPLET')
      ? ['DECLARATION_MENSUELLE', 'CNSS', 'ACOMPTES_PROVISIONNELS']
      : [type];

    let immediateSentCount = 0;

    typesToAdd.forEach((t) => {
      const schedule = computeInitialSchedule(cat, t);
      const itemDue = (dueDateInput && type !== 'PACK_COMPLET') ? dueDateInput : schedule.dueDate;
      const itemSend = (sendDateInput && type !== 'PACK_COMPLET') ? sendDateInput : schedule.sendDate;
      const itemTime = (sendTimeInput && type !== 'PACK_COMPLET') ? sendTimeInput : schedule.sendTime;
      const itemSms = generateSms(name, cat, t, itemDue);

      const isImmediate = schedule.shouldSendImmediately;
      if (isImmediate) immediateSentCount++;

      clients.unshift({
        id: Date.now() + Math.floor(Math.random() * 1000),
        clientName: name,
        category: cat,
        telephone: phone,
        taxId: taxId,
        email: email,
        reminderType: t,
        dueDate: itemDue,
        sendDate: itemSend,
        sendTime: itemTime,
        texteSms: itemSms,
        smsEnvoye: isImmediate
      });
    });

    if (immediateSentCount > 0) {
      alert(`Client enregistré avec succès !\n⚡ Envoi immédiat déclenché pour ${immediateSentCount} échéance(s) (période active). Les cycles suivants seront renouvelés automatiquement.`);
    } else {
      alert("Client et rappels enregistrés avec succès !");
    }
  }

  saveClients();
  resetForm();
}

function resetForm() {
  document.getElementById('edit-id').value = '';
  document.getElementById('client-name').value = '';
  document.getElementById('client-phone').value = '';
  document.getElementById('client-taxid').value = '';
  document.getElementById('client-email').value = '';
  document.getElementById('reminder-type').value = 'DECLARATION_MENSUELLE';
  document.getElementById('form-title').innerText = "➕ Nouveau Client & Rappel";
  document.getElementById('btn-submit').innerText = "💾 Enregistrer le Client & Rappel";

  initFormDates();
  updateSmsPreview();
}

function editClient(id) {
  const item = clients.find(c => c.id == id);
  if (!item) return;

  switchMainTab('reminders');

  document.getElementById('edit-id').value = item.id;
  document.getElementById('client-name').value = item.clientName;
  selectCategory(item.category);
  document.getElementById('client-phone').value = item.telephone;
  document.getElementById('client-taxid').value = item.taxId || '';
  document.getElementById('client-email').value = item.email || '';
  document.getElementById('reminder-type').value = item.reminderType;
  document.getElementById('due-date').value = item.dueDate;
  document.getElementById('send-date').value = item.sendDate;
  document.getElementById('send-time').value = item.sendTime || '09:00';
  document.getElementById('sms-text').value = item.texteSms;
  document.getElementById('sms-char-count').innerText = `${item.texteSms.length} caractères`;

  const details = document.getElementById('manual-options-details');
  if (details) details.open = true;

  document.getElementById('form-title').innerText = "✏️ Modifier le Rappel";
  document.getElementById('btn-submit').innerText = "Mettre à Jour";
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function deleteClient(id) {
  if (confirm("Supprimer ce rappel d'échéance ?")) {
    clients = clients.filter(c => c.id != id);
    saveClients();
  }
}

function resetStatus(id) {
  const item = clients.find(c => c.id == id);
  if (item) {
    item.smsEnvoye = !item.smsEnvoye;
    saveClients();
  }
}

function updateRowSendDate(id, newDate) {
  const item = clients.find(c => c.id == id);
  if (item && newDate) {
    item.sendDate = newDate;
    item.smsEnvoye = false;
    saveClients();
  }
}

// --- VUE 1 : Rendu de la Table des Rappels & SMS ---
function onCategoryFilterChanged(cat) {
  activeCategoryFilter = cat;
  renderTable();
}

function onTypeFilterChanged(type) {
  activeTypeFilter = type;
  renderTable();
}

function syncCycleForActiveDate() {
  const now = getTodayDate();
  const currentYear = now.getFullYear();
  const currentMonth = now.getMonth() + 1; // 1-12
  const monthPad = String(currentMonth).padStart(2, '0');
  const yearMonthPrefix = `${currentYear}-${monthPad}`;

  if (clientDatabase.length === 0 && clients.length > 0) {
    syncClientsToDatabase();
  }
  if (clientDatabase.length === 0) return;

  const daysBefore = config.defaultDaysBefore || 3;
  const defaultTime = config.defaultSendTime || '09:00';
  let changed = false;

  clientDatabase.forEach(cl => {
    const isEntreprise = cl.category === 'ENTREPRISE';

    // 1. Déclaration Mensuelle (DM) - Tous les mois
    const dmDueDay = isEntreprise ? (config.dueDayEntreprise || 28) : (config.dueDayPhysique || 15);
    const dmTarget = new Date(currentYear, currentMonth - 1, dmDueDay);
    const dmDueDate = formatDate(dmTarget);
    const dmSendTarget = new Date(dmTarget);
    dmSendTarget.setDate(dmSendTarget.getDate() - daysBefore);
    const dmSendDate = formatDate(dmSendTarget);

    const hasDm = clients.some(c => (c.telephone === cl.telephone || c.clientName === cl.clientName) && c.reminderType === 'DECLARATION_MENSUELLE' && (c.dueDate && c.dueDate.startsWith(yearMonthPrefix)));
    if (!hasDm) {
      clients.push({
        id: Date.now() + Math.floor(Math.random() * 10000),
        clientName: cl.clientName,
        category: cl.category,
        telephone: cl.telephone,
        email: cl.email || '',
        taxId: cl.taxId || '',
        reminderType: 'DECLARATION_MENSUELLE',
        dueDate: dmDueDate,
        sendDate: dmSendDate,
        sendTime: defaultTime,
        texteSms: generateSmsText(cl.clientName, cl.category, 'DECLARATION_MENSUELLE', dmDueDate),
        smsEnvoye: false
      });
      changed = true;
    }

    // 2. Cotisations CNSS (Janvier, Avril, Juillet, Octobre)
    if ([1, 4, 7, 10].includes(currentMonth)) {
      const cnssDay = config.cnssDueDay || 15;
      const cnssTarget = new Date(currentYear, currentMonth - 1, cnssDay);
      const cnssDueDate = formatDate(cnssTarget);
      const cnssSendTarget = new Date(cnssTarget);
      cnssSendTarget.setDate(cnssSendTarget.getDate() - daysBefore);
      const cnssSendDate = formatDate(cnssSendTarget);

      const hasCnss = clients.some(c => (c.telephone === cl.telephone || c.clientName === cl.clientName) && c.reminderType === 'CNSS' && (c.dueDate && c.dueDate.startsWith(yearMonthPrefix)));
      if (!hasCnss) {
        clients.push({
          id: Date.now() + Math.floor(Math.random() * 10000),
          clientName: cl.clientName,
          category: cl.category,
          telephone: cl.telephone,
          email: cl.email || '',
          taxId: cl.taxId || '',
          reminderType: 'CNSS',
          dueDate: cnssDueDate,
          sendDate: cnssSendDate,
          sendTime: defaultTime,
          texteSms: generateSmsText(cl.clientName, cl.category, 'CNSS', cnssDueDate),
          smsEnvoye: false
        });
        changed = true;
      }
    }

    // 3. Acomptes Provisionnels AP (Juin, Septembre, Décembre)
    if ([6, 9, 12].includes(currentMonth)) {
      const apDay = isEntreprise ? (config.acomptesDayEntreprise || 28) : (config.acomptesDayPhysique || 25);
      const apTarget = new Date(currentYear, currentMonth - 1, apDay);
      const apDueDate = formatDate(apTarget);
      const apSendTarget = new Date(apTarget);
      apSendTarget.setDate(apSendTarget.getDate() - daysBefore);
      const apSendDate = formatDate(apSendTarget);

      const hasAp = clients.some(c => (c.telephone === cl.telephone || c.clientName === cl.clientName) && c.reminderType === 'ACOMPTES_PROVISIONNELS' && (c.dueDate && c.dueDate.startsWith(yearMonthPrefix)));
      if (!hasAp) {
        clients.push({
          id: Date.now() + Math.floor(Math.random() * 10000),
          clientName: cl.clientName,
          category: cl.category,
          telephone: cl.telephone,
          email: cl.email || '',
          taxId: cl.taxId || '',
          reminderType: 'ACOMPTES_PROVISIONNELS',
          dueDate: apDueDate,
          sendDate: apSendDate,
          sendTime: defaultTime,
          texteSms: generateSmsText(cl.clientName, cl.category, 'ACOMPTES_PROVISIONNELS', apDueDate),
          smsEnvoye: false
        });
        changed = true;
      }
    }
  });

  if (changed) {
    localStorage.setItem('comptapocket_clients', JSON.stringify(clients));
  }
}

function getActiveReminders() {
  syncCycleForActiveDate();
  const now = getTodayDate();
  const currentYear = now.getFullYear();
  const currentMonth = now.getMonth() + 1;

  return clients.filter(c => {
    let itemYear = currentYear;
    let itemMonth = currentMonth;
    if (c.dueDate) {
      const parts = c.dueDate.split('-');
      itemYear = parseInt(parts[0]);
      itemMonth = parseInt(parts[1]);
    }

    // Règle 1 : SMS du mois précédent ne polluent pas le mois actif
    const isFromPastMonth = (itemYear < currentYear) || (itemYear === currentYear && itemMonth < currentMonth);
    if (isFromPastMonth) return false;

    // Règle 2 : Cotisations CNSS et AP n'apparaissent dès que le mois de l'échéance entre en vigueur
    if (c.reminderType === 'CNSS' && ![1, 4, 7, 10].includes(currentMonth)) return false;
    if (c.reminderType === 'ACOMPTES_PROVISIONNELS' && ![6, 9, 12].includes(currentMonth)) return false;

    const isCurrentCycle = (itemYear === currentYear && itemMonth === currentMonth);
    const isNextMonth = (itemYear === currentYear && itemMonth === currentMonth + 1) ||
      (currentMonth === 12 && itemYear === currentYear + 1 && itemMonth === 1);
    const isNextMonthDm = !c.smsEnvoye && c.reminderType === 'DECLARATION_MENSUELLE' && isNextMonth;

    return isCurrentCycle || isNextMonthDm;
  });
}

function renderTable() {
  const tbody = document.getElementById('clients-table-body');
  if (!tbody) return;
  tbody.innerHTML = '';

  const activeList = getActiveReminders();

  const filtered = activeList.filter(c => {
    const matchCat = (activeCategoryFilter === 'ALL' || c.category === activeCategoryFilter);
    const matchType = (activeTypeFilter === 'ALL' || c.reminderType === activeTypeFilter);

    const matchQuery = !searchQuery ||
      c.clientName.toLowerCase().includes(searchQuery) ||
      c.telephone.toLowerCase().includes(searchQuery) ||
      (c.taxId && c.taxId.toLowerCase().includes(searchQuery));

    return matchCat && matchType && matchQuery;
  });

  if (filtered.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7" style="text-align:center; padding:2rem; color:var(--text-muted);">Aucun client ni rappel trouvé pour ces critères de recherche.</td></tr>`;
    return;
  }

  filtered.forEach(c => {
    const tr = document.createElement('tr');

    // Catégorie : SYMBOLE SEUL SANS TEXTE (🏢 ou 👤)
    const catBadge = c.category === 'ENTREPRISE'
      ? `<span style="font-size:1.3rem;" title="Société / Entreprise">🏢</span>`
      : `<span style="font-size:1.3rem;" title="Personne physique">👤</span>`;

    // Statut : SANS le mot "Mention", juste "En attente" ou "Envoyé"
    const statusBadge = c.smsEnvoye
      ? `<span class="mention-badge sent" style="cursor:pointer;" onclick="resetStatus(${c.id})" title="Cliquer pour réinitialiser">✅ Envoyé</span>`
      : `<span class="mention-badge pending" style="cursor:pointer;" onclick="resetStatus(${c.id})" title="Cliquer pour marquer envoyé">⏳ En attente</span>`;

    // Type du SMS : SYMBOLE + ABRÉVIATION UNIQUEMENT (📄 DM, 🛡️ CNSS, 💰 AP)
    let typeBadge = "";
    if (c.reminderType === 'DECLARATION_MENSUELLE') {
      typeBadge = `<span class="badge badge-mensuelle" style="font-weight:700;">📄 DM</span>`;
    } else if (c.reminderType === 'CNSS') {
      typeBadge = `<span class="badge badge-cnss" style="font-weight:700;">🛡️ CNSS</span>`;
    } else if (c.reminderType === 'ACOMPTES_PROVISIONNELS') {
      typeBadge = `<span class="badge badge-acomptes" style="font-weight:700;">💰 AP</span>`;
    } else {
      typeBadge = `<span class="badge" style="background:#f1f5f9; color:#475569; font-weight:700;">📌 Autre</span>`;
    }

    tr.innerHTML = `
      <td>
        <strong>${escapeHtml(c.clientName)}</strong><br>
        <small style="color:var(--text-muted);">📱 ${c.telephone} ${c.taxId ? '• MF: ' + escapeHtml(c.taxId) : ''}</small>
      </td>
      <td style="text-align:center;">${catBadge}</td>
      <td>${typeBadge}</td>
      <td><strong>${c.dueDate}</strong></td>
      <td>
        <input type="date" value="${c.sendDate}" style="padding:2px 6px; font-size:0.8rem; border:1px solid var(--border); border-radius:4px;" onchange="updateRowSendDate(${c.id}, this.value)" title="Modifier date d'envoi individuellement">
        <br><small style="color:var(--text-muted);">${c.sendTime || '09:00'}</small>
      </td>
      <td>${statusBadge}</td>
      <td>
        <div class="action-btns">
          <button class="btn btn-sm btn-primary" onclick="sendSmsRow(${c.id})" title="Envoyer par SMS">💬</button>
          <button class="btn btn-sm btn-gold" onclick="sendWhatsAppRow(${c.id})" title="Envoyer via WhatsApp">🟢</button>
          <button class="btn btn-sm btn-secondary" onclick="editClient(${c.id})" style="color:var(--text-main); background:#e2e8f0;" title="Modifier">✏️</button>
          <button class="btn btn-sm" onclick="deleteClient(${c.id})" style="color:#d32f2f; background:#ffebee;" title="Supprimer">🗑️</button>
        </div>
      </td>
    `;
    tbody.appendChild(tr);
  });
}

// --- VUE 2 : Rendu de la Base de Données Clients ---
function setDbCategoryFilter(cat) {
  dbCategoryFilter = cat;
  document.getElementById('btn-db-filter-all').classList.toggle('active', cat === 'ALL');
  document.getElementById('btn-db-filter-ent').classList.toggle('active', cat === 'ENTREPRISE');
  document.getElementById('btn-db-filter-phy').classList.toggle('active', cat === 'PERSONNE_PHYSIQUE');
  renderDatabaseTable();
}

function renderDatabaseTable() {
  const tbody = document.getElementById('db-table-body');
  if (!tbody) return;
  tbody.innerHTML = '';

  const filtered = clientDatabase.filter(c => {
    const matchCat = (dbCategoryFilter === 'ALL' || c.category === dbCategoryFilter);
    const matchQuery = !dbSearchQuery ||
      c.clientName.toLowerCase().includes(dbSearchQuery) ||
      c.telephone.toLowerCase().includes(dbSearchQuery) ||
      (c.taxId && c.taxId.toLowerCase().includes(dbSearchQuery)) ||
      (c.email && c.email.toLowerCase().includes(dbSearchQuery));

    return matchCat && matchQuery;
  });

  if (filtered.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7" style="text-align:center; padding:2rem; color:var(--text-muted);">Aucun client trouvé dans la base de données.</td></tr>`;
    return;
  }

  filtered.forEach(c => {
    const tr = document.createElement('tr');

    const catBadge = c.category === 'ENTREPRISE'
      ? `<span class="badge badge-company">🏢 Société</span>`
      : `<span class="badge badge-person">👤 Pers. Physique</span>`;

    // Recherche de tous les rappels actifs associés à ce client
    const userReminders = clients.filter(r => r.telephone === c.telephone || r.clientName === c.clientName);

    let remindersHtml = '';
    if (userReminders.length === 0) {
      remindersHtml = `<span style="font-size:0.75rem; color:var(--text-muted);">Aucun rappel actif</span>`;
    } else {
      userReminders.forEach(rem => {
        let label = "Décl. Mensuelle";
        let badgeClass = "badge-mensuelle";
        if (rem.reminderType === 'CNSS') {
          label = "CNSS";
          badgeClass = "badge-cnss";
        } else if (rem.reminderType === 'ACOMPTES_PROVISIONNELS') {
          label = "Acomptes";
          badgeClass = "badge-acomptes";
        }

        const mentionBadge = rem.smsEnvoye
          ? `<span style="color:#2e7d32; font-weight:800; font-size:0.75rem;">[Envoyé]</span>`
          : `<span style="color:#e65100; font-weight:800; font-size:0.75rem;">[En attente]</span>`;

        remindersHtml += `<div style="display:inline-block; margin-bottom:2px;"><span class="reminder-chip ${badgeClass}">${label}</span> ${mentionBadge}</div> `;
      });
    }

    tr.innerHTML = `
      <td><strong>${escapeHtml(c.clientName)}</strong></td>
      <td>${catBadge}</td>
      <td><a href="tel:${escapeHtml(c.telephone)}" style="color:var(--primary); font-weight:600; text-decoration:none;">📱 ${escapeHtml(c.telephone)}</a></td>
      <td>${c.taxId ? escapeHtml(c.taxId) : '<span style="color:var(--text-muted);">-</span>'}</td>
      <td>${c.email ? escapeHtml(c.email) : '<span style="color:var(--text-muted);">-</span>'}</td>
      <td>${remindersHtml}</td>
      <td>
        <div class="action-btns">
          <button class="btn btn-sm btn-primary" onclick="openAddReminderForClient(${c.id})" title="Ajouter un rappel">+ Rappel</button>
          <button class="btn btn-sm btn-secondary" onclick="openEditClientModal(${c.id})" style="color:var(--text-main); background:#e2e8f0;" title="Modifier les coordonnées">✏️ Modifier</button>
          <button class="btn btn-sm" onclick="deleteClientFromDatabase(${c.id})" style="color:#d32f2f; background:#ffebee;" title="Supprimer de la base">🗑️</button>
        </div>
      </td>
    `;
    tbody.appendChild(tr);
  });
}

// --- Modale de Modification des Données d'un Client ---
function openAddClientModal() {
  document.getElementById('modal-client-title').innerText = "➕ Ajouter un Client dans la Base";
  document.getElementById('edit-client-orig-id').value = '';
  document.getElementById('edit-client-orig-name').value = '';
  document.getElementById('edit-client-orig-phone').value = '';
  document.getElementById('edit-client-name').value = '';
  setEditClientCategory('ENTREPRISE');
  document.getElementById('edit-client-phone').value = '';
  document.getElementById('edit-client-taxid').value = '';
  document.getElementById('edit-client-email').value = '';
  document.getElementById('edit-client-address').value = '';
  document.getElementById('edit-client-notes').value = '';

  document.getElementById('modal-edit-client').classList.add('active');
}

function openEditClientModal(id) {
  const client = clientDatabase.find(c => c.id == id);
  if (!client) return;

  document.getElementById('modal-client-title').innerText = "✏️ Modifier les Données du Client";
  document.getElementById('edit-client-orig-id').value = client.id;
  document.getElementById('edit-client-orig-name').value = client.clientName;
  document.getElementById('edit-client-orig-phone').value = client.telephone;
  document.getElementById('edit-client-name').value = client.clientName;
  setEditClientCategory(client.category);
  document.getElementById('edit-client-phone').value = client.telephone;
  document.getElementById('edit-client-taxid').value = client.taxId || '';
  document.getElementById('edit-client-email').value = client.email || '';
  document.getElementById('edit-client-address').value = client.address || '';
  document.getElementById('edit-client-notes').value = client.notes || '';

  document.getElementById('modal-edit-client').classList.add('active');
}

function closeEditClientModal() {
  document.getElementById('modal-edit-client').classList.remove('active');
}

function setEditClientCategory(cat) {
  document.getElementById('edit-client-category').value = cat;
  document.getElementById('btn-edit-cat-ent').classList.toggle('active', cat === 'ENTREPRISE');
  document.getElementById('btn-edit-cat-phy').classList.toggle('active', cat === 'PERSONNE_PHYSIQUE');
}

function saveClientFromModal() {
  const origId = document.getElementById('edit-client-orig-id').value;
  const origName = document.getElementById('edit-client-orig-name').value;
  const origPhone = document.getElementById('edit-client-orig-phone').value;

  const newName = document.getElementById('edit-client-name').value.trim();
  const newCat = document.getElementById('edit-client-category').value;
  const newPhone = document.getElementById('edit-client-phone').value.trim();
  const newTaxId = document.getElementById('edit-client-taxid').value.trim();
  const newEmail = document.getElementById('edit-client-email').value.trim();
  const newAddress = document.getElementById('edit-client-address').value.trim();
  const newNotes = document.getElementById('edit-client-notes').value.trim();

  if (!newName || !newPhone) {
    alert("Veuillez renseigner au minimum le nom et le numéro mobile.");
    return;
  }

  if (origId) {
    // Mise à jour client existant
    const idx = clientDatabase.findIndex(c => c.id == origId);
    if (idx !== -1) {
      clientDatabase[idx] = Object.assign(clientDatabase[idx], {
        clientName: newName,
        category: newCat,
        telephone: newPhone,
        taxId: newTaxId,
        email: newEmail,
        address: newAddress,
        notes: newNotes
      });
    }

    // Mise à jour en cascade de tous les rappels existants pour ce client
    let cascadedCount = 0;
    clients.forEach(r => {
      if (r.telephone === origPhone || r.clientName === origName) {
        r.clientName = newName;
        r.category = newCat;
        r.telephone = newPhone;
        r.taxId = newTaxId;
        r.email = newEmail;
        if (!r.smsEnvoye) {
          r.texteSms = generateSms(newName, newCat, r.reminderType, r.dueDate);
        }
        cascadedCount++;
      }
    });

    saveClientDatabase();
    localStorage.setItem('comptapocket_clients', JSON.stringify(clients));
    closeEditClientModal();
    renderTable();
    renderDatabaseTable();
    alert(`Données du client mises à jour avec succès !\n${cascadedCount} rappel(s) associé(s) et messages SMS ont été automatiquement actualisés.`);
  } else {
    // Nouveau client dans la base
    const newClient = {
      id: Date.now() + Math.floor(Math.random() * 1000),
      clientName: newName,
      category: newCat,
      telephone: newPhone,
      taxId: newTaxId,
      email: newEmail,
      address: newAddress,
      notes: newNotes
    };
    clientDatabase.unshift(newClient);
    saveClientDatabase();
    closeEditClientModal();
    alert("Client ajouté avec succès à la base de données !");
  }
}

function deleteClientFromDatabase(id) {
  const client = clientDatabase.find(c => c.id == id);
  if (!client) return;

  if (confirm(`Voulez-vous supprimer le client "${client.clientName}" de la base de données ?\n\nSes rappels d'échéances associés seront également supprimés.`)) {
    clientDatabase = clientDatabase.filter(c => c.id != id);
    clients = clients.filter(r => r.telephone !== client.telephone && r.clientName !== client.clientName);

    saveClientDatabase();
    localStorage.setItem('comptapocket_clients', JSON.stringify(clients));
    renderTable();
    renderDatabaseTable();
    updateStats();
  }
}

function openAddReminderForClient(id) {
  const client = clientDatabase.find(c => c.id == id);
  if (!client) return;

  switchMainTab('reminders');

  document.getElementById('edit-id').value = '';
  document.getElementById('client-name').value = client.clientName;
  selectCategory(client.category);
  document.getElementById('client-phone').value = client.telephone;
  document.getElementById('client-taxid').value = client.taxId || '';
  document.getElementById('client-email').value = client.email || '';
  document.getElementById('reminder-type').value = 'DECLARATION_MENSUELLE';

  initFormDates();
  updateSmsPreview();

  document.getElementById('form-title').innerText = `➕ Nouveau Rappel pour ${client.clientName}`;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

// --- Mise à jour des Statistiques Globales ---
function updateStats() {
  const activeList = getActiveReminders();
  const compEl = document.getElementById('stat-companies');
  if (compEl) compEl.innerText = activeList.filter(c => c.category === 'ENTREPRISE').length;

  const persEl = document.getElementById('stat-persons');
  if (persEl) persEl.innerText = activeList.filter(c => c.category === 'PERSONNE_PHYSIQUE').length;

  const pendEl = document.getElementById('stat-pending');
  if (pendEl) pendEl.innerText = activeList.filter(c => !c.smsEnvoye).length;

  const sentEl = document.getElementById('stat-sent');
  if (sentEl) sentEl.innerText = activeList.filter(c => c.smsEnvoye).length;

  // Stats Database
  const dbTotal = document.getElementById('db-stat-total');
  if (dbTotal) dbTotal.innerText = clientDatabase.length;

  const dbComp = document.getElementById('db-stat-companies');
  if (dbComp) dbComp.innerText = clientDatabase.filter(c => c.category === 'ENTREPRISE').length;

  const dbPers = document.getElementById('db-stat-persons');
  if (dbPers) dbPers.innerText = clientDatabase.filter(c => c.category === 'PERSONNE_PHYSIQUE').length;

  const tabCount = document.getElementById('tab-client-count');
  if (tabCount) tabCount.innerText = clientDatabase.length;
}
  if (dbComp) dbComp.innerText = clientDatabase.filter(c => c.category === 'ENTREPRISE').length;

  const dbPers = document.getElementById('db-stat-persons');
  if (dbPers) dbPers.innerText = clientDatabase.filter(c => c.category === 'PERSONNE_PHYSIQUE').length;

  const tabCount = document.getElementById('tab-client-count');
  if (tabCount) tabCount.innerText = clientDatabase.length;
}

// --- Batch Modification Modal ---
function openBatchModal() {
  document.getElementById('batch-modal').classList.add('active');
}

function closeBatchModal() {
  document.getElementById('batch-modal').classList.remove('active');
}

function setBatchMode(mode) {
  batchMode = mode;
  if (mode === 'EXACT') {
    document.getElementById('btn-batch-mode-exact').classList.add('active');
    document.getElementById('btn-batch-mode-shift').classList.remove('active');
    document.getElementById('batch-exact-fields').style.display = 'grid';
    document.getElementById('batch-shift-fields').style.display = 'none';
  } else {
    document.getElementById('btn-batch-mode-shift').classList.add('active');
    document.getElementById('btn-batch-mode-exact').classList.remove('active');
    document.getElementById('batch-exact-fields').style.display = 'none';
    document.getElementById('batch-shift-fields').style.display = 'block';
  }
}

function applyBatchDate() {
  const radios = document.getElementsByName('batch-target');
  let target = 'ALL';
  for (const r of radios) {
    if (r.checked) target = r.value;
  }

  let count = 0;
  if (batchMode === 'EXACT') {
    const newDate = document.getElementById('batch-date').value;
    const newTime = document.getElementById('batch-time').value || '09:00';
    if (!newDate) {
      alert("Veuillez sélectionner une date d'envoi valide.");
      return;
    }

    clients.forEach(c => {
      if (!c.smsEnvoye && (target === 'ALL' || c.category === target)) {
        c.sendDate = newDate;
        c.sendTime = newTime;
        count++;
      }
    });
  } else {
    const shift = parseInt(document.getElementById('batch-shift-days').value) || 0;
    clients.forEach(c => {
      if (!c.smsEnvoye && (target === 'ALL' || c.category === target)) {
        try {
          const parts = c.sendDate.split('-');
          const d = new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
          d.setDate(d.getDate() + shift);
          c.sendDate = formatDate(d);
          count++;
        } catch (e) {}
      }
    });
  }

  saveClients();
  closeBatchModal();
  alert(`Date d'envoi mise à jour pour ${count} client(s) !`);
}

// --- SMS & WhatsApp ---
function sendSmsRow(id) {
  const item = clients.find(c => c.id == id);
  if (!item) return;

  const cleanPhone = item.telephone.replace(/[\s-]/g, '');
  const encodedMsg = encodeURIComponent(item.texteSms);

  item.smsEnvoye = true;
  saveClients();

  // Enclencher automatiquement le cycle périodique suivant
  scheduleNextPeriodicReminder(item);

  window.open(`sms:${cleanPhone}?body=${encodedMsg}`, '_blank');
}

function sendWhatsAppRow(id) {
  const item = clients.find(c => c.id == id);
  if (!item) return;

  let cleanPhone = item.telephone.replace(/[\s-]/g, '');
  if (!cleanPhone.startsWith('+') && !cleanPhone.startsWith('00')) {
    cleanPhone = '216' + cleanPhone;
  }
  cleanPhone = cleanPhone.replace('+', '');

  item.smsEnvoye = true;
  saveClients();

  // Enclencher automatiquement le cycle périodique suivant
  scheduleNextPeriodicReminder(item);

  window.open(`https://wa.me/${cleanPhone}?text=${encodeURIComponent(item.texteSms)}`, '_blank');
}

function onSendAllPending() {
  const pending = clients.filter(c => !c.smsEnvoye);
  if (pending.length === 0) {
    alert("Aucun SMS en attente d'envoi.");
    return;
  }

  if (confirm(`Voulez-vous marquer comme envoyés et préparer l'envoi pour ${pending.length} clients en attente ?`)) {
    pending.forEach(c => {
      c.smsEnvoye = true;
      scheduleNextPeriodicReminder(c);
    });
    saveClients();
    alert(`Les ${pending.length} SMS ont été validés et les rappels pour les cycles suivants ont été programmés périodiquement !`);
  }
}

// --- Réalignement automatique sur les réglages ---
function realignAllClientsWithFixedSettings() {
  let count = 0;
  clients.forEach(c => {
    if (!c.smsEnvoye) {
      const schedule = computeInitialSchedule(c.category, c.reminderType);
      c.dueDate = schedule.dueDate;
      c.sendDate = schedule.sendDate;
      c.sendTime = schedule.sendTime;
      c.texteSms = generateSms(c.clientName, c.category, c.reminderType, schedule.dueDate);
      count++;
    }
  });

  saveClients();
  alert(`Tous les clients (${count}) ont été réalignés sur les dates automatiques des réglages !`);
}

// --- Modal Paramètres ---
function openSettingsModal() {
  document.getElementById('setting-auto-send').checked = config.autoSend !== false;
  document.getElementById('setting-cabinet-name').value = config.cabinetName || '';
  document.getElementById('setting-cabinet-phone').value = config.cabinetPhone || '';
  document.getElementById('setting-due-entreprise').value = config.dueDayEntreprise || 28;
  document.getElementById('setting-due-physique').value = config.dueDayPhysique || 15;
  document.getElementById('setting-due-cnss').value = config.cnssDueDay || 15;
  document.getElementById('setting-acomptes-entreprise').value = config.acomptesDayEntreprise || 28;
  document.getElementById('setting-acomptes-physique').value = config.acomptesDayPhysique || 25;
  document.getElementById('setting-days-before').value = config.defaultDaysBefore || 3;
  document.getElementById('setting-template-mensuelle').value = config.templates.mensuelle || '';
  document.getElementById('setting-template-cnss').value = config.templates.cnss || '';
  document.getElementById('setting-template-acomptes').value = config.templates.acomptes || '';

  document.getElementById('settings-modal').classList.add('active');
}

function closeSettingsModal() {
  document.getElementById('settings-modal').classList.remove('active');
}

function saveSettings() {
  config.autoSend = document.getElementById('setting-auto-send').checked;
  config.cabinetName = document.getElementById('setting-cabinet-name').value.trim() || "Bureau de Comptabilité";
  config.cabinetPhone = document.getElementById('setting-cabinet-phone').value.trim();
  config.dueDayEntreprise = parseInt(document.getElementById('setting-due-entreprise').value) || 28;
  config.dueDayPhysique = parseInt(document.getElementById('setting-due-physique').value) || 15;
  config.cnssDueDay = parseInt(document.getElementById('setting-due-cnss').value) || 15;
  config.acomptesDayEntreprise = parseInt(document.getElementById('setting-acomptes-entreprise').value) || 28;
  config.acomptesDayPhysique = parseInt(document.getElementById('setting-acomptes-physique').value) || 25;
  config.defaultDaysBefore = parseInt(document.getElementById('setting-days-before').value) || 3;
  config.templates.mensuelle = document.getElementById('setting-template-mensuelle').value.trim();
  config.templates.cnss = document.getElementById('setting-template-cnss').value.trim();
  config.templates.acomptes = document.getElementById('setting-template-acomptes').value.trim();

  saveConfig(config);
  closeSettingsModal();
  updateSmsPreview();
  alert("Paramètres enregistrés avec succès !");
}

function applyConfigUI() {
  const el = document.getElementById('header-cabinet-name');
  if (el) el.innerText = `${config.cabinetName} • Échéances Fiscales & Sociales`;
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// --- Sauvegarde & Restauration de la Base de Données ---
function exportDatabaseFile() {
  const data = {
    version: 2,
    exportDate: new Date().toISOString(),
    cabinet: config.cabinetName,
    clients: clientDatabase,
    reminders: clients
  };
  const str = JSON.stringify(data, null, 2);
  const blob = new Blob([str], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const d = new Date();
  const dateStr = `${d.getFullYear()}${String(d.getMonth()+1).padStart(2,'0')}${String(d.getDate()).padStart(2,'0')}`;
  a.href = url;
  a.download = `comptapocket_backup_${dateStr}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  alert("Sauvegarde de la base de données téléchargée avec succès !");
}

function importDatabaseFile(event) {
  const file = event.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = function(e) {
    try {
      const data = JSON.parse(e.target.result);
      if (data.clients && Array.isArray(data.clients)) {
        clientDatabase = data.clients;
        localStorage.setItem('comptapocket_db_clients', JSON.stringify(clientDatabase));
      }
      if (data.reminders && Array.isArray(data.reminders)) {
        clients = data.reminders;
        localStorage.setItem('comptapocket_clients', JSON.stringify(clients));
      }
      renderTable();
      renderDatabaseTable();
      updateStats();
      alert("Base de données clients et échéances restaurées avec succès !");
      closeSettingsModal();
    } catch (err) {
      alert("Fichier de sauvegarde invalide : " + err.message);
    }
  };
  reader.readAsText(file);
  event.target.value = '';
}

// --- Modal À propos & Mises à jour ---
function openAboutModal() {
  const modal = document.getElementById('about-modal');
  if (modal) modal.style.display = 'flex';
}

function closeAboutModal() {
  const modal = document.getElementById('about-modal');
  if (modal) modal.style.display = 'none';
}

async function checkGitHubUpdate() {
  const statusBox = document.getElementById('update-status-text');
  const btn = document.getElementById('btn-check-update');
  if (statusBox) statusBox.innerHTML = '⏳ Vérification en cours sur GitHub...';
  if (btn) btn.disabled = true;

  try {
    const res = await fetch('https://raw.githubusercontent.com/bkhmondocteur/comptapocket-sms/main/version.json', { cache: 'no-store' });
    if (res.ok) {
      const data = await res.json();
      if (data.versionName && data.versionName !== '1.0.1') {
        statusBox.innerHTML = `🚀 <strong>Nouvelle version v${data.versionName} disponible !</strong><br><small style="color:#64748b;">${data.changelog ? data.changelog.replace(/\n/g, '<br>') : ''}</small>`;
      } else {
        statusBox.innerHTML = '✅ <strong>Votre application est parfaitement à jour (v1.0.1).</strong>';
      }
    } else {
      statusBox.innerHTML = '✅ Votre application est à jour (v1.0.1).';
    }
  } catch (err) {
    statusBox.innerHTML = 'ℹ️ Impossible de joindre GitHub ou connexion hors-ligne.';
  } finally {
    if (btn) btn.disabled = false;
  }
}

// --- Mode Test & Simulation de Date ---
function updateSimulationUI() {
  const banner = document.getElementById('simulation-banner');
  const title = document.getElementById('sim-banner-title');
  const subtitle = document.getElementById('sim-banner-subtitle');
  const resetBtn = document.getElementById('btn-reset-sim');
  const modalStatus = document.getElementById('sim-modal-status');
  const modalDisplay = document.getElementById('sim-modal-display-date');
  const inputDate = document.getElementById('sim-input-date');

  const d = getTodayDate();
  const dateFormatted = formatDate(d);
  const options = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' };
  const displayFormatted = d.toLocaleDateString('fr-FR', options);

  if (inputDate) inputDate.value = dateFormatted;

  if (simulatedDate) {
    if (banner) {
      banner.style.background = '#fff7ed';
      banner.style.borderColor = '#fdba74';
    }
    if (title) title.innerText = `🧪 Simulation Active : ${displayFormatted}`;
    if (subtitle) subtitle.innerText = "Date virtuelle en vigueur pour toutes les échéances (DM, CNSS, AP).";
    if (resetBtn) resetBtn.style.display = 'inline-block';
    if (modalStatus) {
      modalStatus.innerText = "⚠️ SIMULATION DE DATE ACTIVE";
      modalStatus.style.color = "#ea580c";
    }
    if (modalDisplay) {
      modalDisplay.innerText = displayFormatted;
      modalDisplay.style.color = "#c2410c";
    }
  } else {
    if (banner) {
      banner.style.background = '#f0fdf4';
      banner.style.borderColor = '#bbf7d0';
    }
    if (title) title.innerText = `📅 Date Réelle : ${displayFormatted}`;
    if (subtitle) subtitle.innerText = "Mode test : cliquez pour simuler une autre date et tester les échéances.";
    if (resetBtn) resetBtn.style.display = 'none';
    if (modalStatus) {
      modalStatus.innerText = "✅ DATE RÉELLE DU SYSTÈME";
      modalStatus.style.color = "#059669";
    }
    if (modalDisplay) {
      modalDisplay.innerText = displayFormatted;
      modalDisplay.style.color = "#166534";
    }
  }
}

function openSimulationModal() {
  updateSimulationUI();
  const modal = document.getElementById('simulation-modal');
  if (modal) modal.style.display = 'flex';
}

function closeSimulationModal() {
  const modal = document.getElementById('simulation-modal');
  if (modal) modal.style.display = 'none';
}

function applyCustomSimDate() {
  const input = document.getElementById('sim-input-date');
  if (input && input.value) {
    simulatedDate = input.value;
    localStorage.setItem('comptapocket_simulated_date', simulatedDate);
    updateSimulationUI();
    renderTable();
    updateStats();
    initFormDates();
    closeSimulationModal();
  }
}

function shiftSimDays(days) {
  const d = getTodayDate();
  d.setDate(d.getDate() + days);
  simulatedDate = formatDate(d);
  localStorage.setItem('comptapocket_simulated_date', simulatedDate);
  updateSimulationUI();
  renderTable();
  updateStats();
  initFormDates();
}

function shiftSimMonths(months) {
  const d = getTodayDate();
  d.setMonth(d.getMonth() + months);
  simulatedDate = formatDate(d);
  localStorage.setItem('comptapocket_simulated_date', simulatedDate);
  updateSimulationUI();
  renderTable();
  updateStats();
  initFormDates();
}

function setSimSpecificDay(day) {
  const d = getTodayDate();
  d.setDate(day);
  simulatedDate = formatDate(d);
  localStorage.setItem('comptapocket_simulated_date', simulatedDate);
  updateSimulationUI();
  renderTable();
  updateStats();
  initFormDates();
}

function setSimKeyDate(monthIndex, day) {
  const d = getTodayDate();
  const currentM = d.getMonth();
  const y = d.getFullYear();
  const targetYear = (monthIndex < currentM) ? y + 1 : y;
  const target = new Date(targetYear, monthIndex, day);
  simulatedDate = formatDate(target);
  localStorage.setItem('comptapocket_simulated_date', simulatedDate);
  updateSimulationUI();
  syncCycleForActiveDate();
  renderTable();
  updateStats();
  initFormDates();
}

function resetSimulationDate() {
  simulatedDate = null;
  localStorage.removeItem('comptapocket_simulated_date');
  updateSimulationUI();
  renderTable();
  updateStats();
  initFormDates();
  closeSimulationModal();
}

// --- Service Worker ---
function registerSW() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
}
