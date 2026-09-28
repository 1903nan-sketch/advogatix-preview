(() => {
  const core = window.AdvogaCore;
  if (!core) return;

  const {
    supabase, state, $, $$, esc, norm, brDate, toIso, toLocalInput,
    toast, setStatus, setBusy, openDialog, closeDialog, badge,
    clientById, caseById
  } = core;

  const priorityLabels = { low: "Baixa", normal: "Normal", high: "Alta", urgent: "Urgente" };
  const priorityTypes = { low: "", normal: "", high: "warn", urgent: "err" };
  const deadlineStatusLabels = { pending: "Pendente", in_progress: "Em andamento", completed: "Concluído", cancelled: "Cancelado" };
  const taskStatusLabels = { todo: "A fazer", in_progress: "Em andamento", waiting: "Aguardando", completed: "Concluída", cancelled: "Cancelada" };
  const eventTypeLabels = { hearing: "Audiência", expert_exam: "Perícia", meeting: "Reunião", deadline: "Prazo", court_visit: "Diligência", other: "Compromisso" };
  const financeTypeLabels = {
    fee: "Honorários", success_fee: "Honorários de êxito", cost: "Custas",
    expense: "Despesa", client_transfer: "Repasse ao cliente",
    other_receivable: "Outro recebimento", other_payable: "Outro pagamento"
  };
  const financeStatusLabels = { pending: "Pendente", paid: "Pago", overdue: "Vencido", cancelled: "Cancelado" };
  const leadStatusLabels = { lead: "Interessado", consultation: "Consulta", proposal: "Proposta", contracted: "Contratado", lost: "Perdido" };
  const payableTypes = new Set(["cost", "expense", "client_transfer", "other_payable"]);

  function money(value) {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(Number(value || 0));
  }

  function localDateKey(value) {
    if (!value) return "";
    const d = new Date(value);
    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit", day: "2-digit"
    }).formatToParts(d);
    const get = (type) => parts.find((p) => p.type === type)?.value || "";
    return get("year") + "-" + get("month") + "-" + get("day");
  }

  function todayKey() {
    return localDateKey(new Date());
  }

  function isToday(value) {
    return !!value && localDateKey(value) === todayKey();
  }

  function isOverdue(value, status) {
    if (!value || ["completed", "cancelled", "paid"].includes(status)) return false;
    return new Date(value).getTime() < Date.now() && !isToday(value);
  }

  function dueClass(value, status) {
    if (isOverdue(value, status)) return "due-overdue";
    if (isToday(value)) return "due-today";
    return "";
  }

  function clientOptions(selected, allowEmpty = true) {
    const empty = allowEmpty ? '<option value="">Sem cliente</option>' : "";
    return empty + state.clients
      .filter((c) => c.status === "active" || c.id === selected)
      .map((c) => '<option value="' + c.id + '"' + (c.id === selected ? " selected" : "") + ">" + esc(c.full_name) + "</option>")
      .join("");
  }

  function caseOptions(selected, allowEmpty = true) {
    const empty = allowEmpty ? '<option value="">Sem processo</option>' : "";
    return empty + state.cases
      .filter((c) => c.status !== "archived" || c.id === selected)
      .map((c) => {
        const client = clientById(c.client_id);
        const num = c.process_number ? " — " + c.process_number : "";
        return '<option value="' + c.id + '"' + (c.id === selected ? " selected" : "") + ">" +
          esc(client?.full_name || "Cliente") + " — " + esc(c.title) + esc(num) + "</option>";
      }).join("");
  }

  function fillOrganizerSelects(values = {}) {
    const clientIds = ["deadlineClient", "taskClient", "eventClient", "financeClient"];
    const caseIds = ["deadlineCase", "taskCase", "eventCase", "financeCase"];
    clientIds.forEach((id) => {
      const el = $("#" + id);
      if (el) el.innerHTML = clientOptions(values[id] || "");
    });
    caseIds.forEach((id) => {
      const el = $("#" + id);
      if (el) el.innerHTML = caseOptions(values[id] || "");
    });
  }

  function syncClientFromCase(caseSelectId, clientSelectId) {
    const caseId = $("#" + caseSelectId)?.value;
    const item = caseById(caseId);
    if (item && $("#" + clientSelectId)) $("#" + clientSelectId).value = item.client_id || "";
  }

  [["deadlineCase","deadlineClient"],["taskCase","taskClient"],["eventCase","eventClient"],["financeCase","financeClient"]]
    .forEach(([caseId, clientId]) => {
      $("#" + caseId)?.addEventListener("change", () => syncClientFromCase(caseId, clientId));
    });

  function openDeadlineDialog(id = null) {
    state.editingDeadlineId = id;
    $("#deadlineForm").reset();
    setStatus($("#deadlineStatusMsg"));
    const item = id ? state.deadlines.find((x) => x.id === id) : null;
    fillOrganizerSelects({
      deadlineCase: item?.case_id || "",
      deadlineClient: item?.client_id || ""
    });
    $("#deadlineDialogTitle").textContent = item ? "Editar prazo" : "Novo prazo";
    $("#deadlineSaveBtn").textContent = item ? "Salvar alterações" : "Salvar prazo";
    $("#deadlinePriority").value = item?.priority || "normal";
    $("#deadlineStatus").value = item?.status || "pending";
    $("#deadlineType").value = item?.deadline_type || "processual";
    if (item) {
      $("#deadlineTitle").value = item.title || "";
      $("#deadlineDue").value = toLocalInput(item.due_at);
      $("#deadlineSource").value = item.source || "";
      $("#deadlineNotes").value = item.notes || "";
    }
    openDialog("deadlineDialog");
  }

  $("#deadlineForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = event.submitter;
    setBusy(button, true, "Salvando");
    const status = $("#deadlineStatus").value;
    const payload = {
      title: $("#deadlineTitle").value.trim(),
      case_id: $("#deadlineCase").value || null,
      client_id: $("#deadlineClient").value || null,
      deadline_type: $("#deadlineType").value,
      due_at: toIso($("#deadlineDue").value),
      priority: $("#deadlinePriority").value,
      status,
      source: $("#deadlineSource").value.trim() || null,
      notes: $("#deadlineNotes").value.trim() || null,
      completed_at: status === "completed" ? new Date().toISOString() : null
    };
    try {
      if (state.editingDeadlineId) {
        const { error } = await supabase.from("deadlines").update(payload)
          .eq("id", state.editingDeadlineId).eq("firm_id", state.firm.id);
        if (error) throw error;
        toast("Prazo atualizado.");
      } else {
        const { error } = await supabase.from("deadlines").insert({
          ...payload, firm_id: state.firm.id, created_by: state.user.id
        });
        if (error) throw error;
        toast("Prazo cadastrado.");
      }
      closeDialog("deadlineDialog");
      await core.loadData();
    } catch (error) {
      setStatus($("#deadlineStatusMsg"), error.message || "Não foi possível salvar o prazo.", "err");
    } finally {
      setBusy(button, false);
    }
  });

  async function completeDeadline(id, button) {
    setBusy(button, true, "Concluindo");
    try {
      const { error } = await supabase.from("deadlines")
        .update({ status: "completed", completed_at: new Date().toISOString() })
        .eq("id", id).eq("firm_id", state.firm.id);
      if (error) throw error;
      toast("Prazo concluído.");
      await core.loadData();
    } catch (error) {
      toast(error.message || "Não foi possível concluir o prazo.", "err");
    } finally {
      setBusy(button, false);
    }
  }

  function openTaskDialog(id = null) {
    state.editingTaskId = id;
    $("#taskForm").reset();
    setStatus($("#taskStatusMsg"));
    const item = id ? state.tasks.find((x) => x.id === id) : null;
    fillOrganizerSelects({
      taskCase: item?.case_id || "",
      taskClient: item?.client_id || ""
    });
    $("#taskDialogTitle").textContent = item ? "Editar tarefa" : "Nova tarefa";
    $("#taskSaveBtn").textContent = item ? "Salvar alterações" : "Salvar tarefa";
    $("#taskPriority").value = item?.priority || "normal";
    $("#taskStatus").value = item?.status || "todo";
    if (item) {
      $("#taskTitle").value = item.title || "";
      $("#taskDue").value = toLocalInput(item.due_at);
      $("#taskDescription").value = item.description || "";
    }
    openDialog("taskDialog");
  }

  $("#taskForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = event.submitter;
    setBusy(button, true, "Salvando");
    const status = $("#taskStatus").value;
    const payload = {
      title: $("#taskTitle").value.trim(),
      description: $("#taskDescription").value.trim() || null,
      case_id: $("#taskCase").value || null,
      client_id: $("#taskClient").value || null,
      due_at: toIso($("#taskDue").value),
      priority: $("#taskPriority").value,
      status,
      assigned_to: state.user.id,
      completed_at: status === "completed" ? new Date().toISOString() : null
    };
    try {
      if (state.editingTaskId) {
        const { error } = await supabase.from("tasks").update(payload)
          .eq("id", state.editingTaskId).eq("firm_id", state.firm.id);
        if (error) throw error;
        toast("Tarefa atualizada.");
      } else {
        const { error } = await supabase.from("tasks").insert({
          ...payload, firm_id: state.firm.id, created_by: state.user.id
        });
        if (error) throw error;
        toast("Tarefa criada.");
      }
      closeDialog("taskDialog");
      await core.loadData();
    } catch (error) {
      setStatus($("#taskStatusMsg"), error.message || "Não foi possível salvar a tarefa.", "err");
    } finally {
      setBusy(button, false);
    }
  });

  async function completeTask(id, button) {
    setBusy(button, true, "Concluindo");
    try {
      const { error } = await supabase.from("tasks")
        .update({ status: "completed", completed_at: new Date().toISOString() })
        .eq("id", id).eq("firm_id", state.firm.id);
      if (error) throw error;
      toast("Tarefa concluída.");
      await core.loadData();
    } catch (error) {
      toast(error.message || "Não foi possível concluir a tarefa.", "err");
    } finally {
      setBusy(button, false);
    }
  }

  function openEventDialog(id = null) {
    state.editingEventId = id;
    $("#eventForm").reset();
    setStatus($("#eventStatusMsg"));
    const item = id ? state.calendarEvents.find((x) => x.id === id) : null;
    fillOrganizerSelects({
      eventCase: item?.case_id || "",
      eventClient: item?.client_id || ""
    });
    $("#eventDialogTitle").textContent = item ? "Editar compromisso" : "Novo compromisso";
    $("#eventSaveBtn").textContent = item ? "Salvar alterações" : "Salvar compromisso";
    $("#eventType").value = item?.event_type || "meeting";
    if (item) {
      $("#eventTitle").value = item.title || "";
      $("#eventStart").value = toLocalInput(item.start_at);
      $("#eventEnd").value = toLocalInput(item.end_at);
      $("#eventModality").value = item.modality || "";
      $("#eventLocation").value = item.location || "";
      $("#eventNotes").value = item.notes || "";
    }
    openDialog("eventDialog");
  }

  $("#eventForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = event.submitter;
    setBusy(button, true, "Salvando");
    const payload = {
      title: $("#eventTitle").value.trim(),
      event_type: $("#eventType").value,
      start_at: toIso($("#eventStart").value),
      end_at: toIso($("#eventEnd").value),
      case_id: $("#eventCase").value || null,
      client_id: $("#eventClient").value || null,
      modality: $("#eventModality").value || null,
      location: $("#eventLocation").value.trim() || null,
      notes: $("#eventNotes").value.trim() || null,
      responsible_user_id: state.user.id
    };
    try {
      if (state.editingEventId) {
        const { error } = await supabase.from("calendar_events").update(payload)
          .eq("id", state.editingEventId).eq("firm_id", state.firm.id);
        if (error) throw error;
        toast("Compromisso atualizado.");
      } else {
        const { error } = await supabase.from("calendar_events").insert({
          ...payload, firm_id: state.firm.id, created_by: state.user.id
        });
        if (error) throw error;
        toast("Compromisso criado.");
      }
      closeDialog("eventDialog");
      await core.loadData();
    } catch (error) {
      setStatus($("#eventStatusMsg"), error.message || "Não foi possível salvar o compromisso.", "err");
    } finally {
      setBusy(button, false);
    }
  });

  function openFinanceDialog(id = null) {
    state.editingFinanceId = id;
    $("#financeForm").reset();
    setStatus($("#financeStatusMsg"));
    const item = id ? state.financialEntries.find((x) => x.id === id) : null;
    fillOrganizerSelects({
      financeCase: item?.case_id || "",
      financeClient: item?.client_id || ""
    });
    $("#financeDialogTitle").textContent = item ? "Editar lançamento" : "Novo lançamento";
    $("#financeSaveBtn").textContent = item ? "Salvar alterações" : "Salvar lançamento";
    $("#financeType").value = item?.entry_type || "fee";
    $("#financeStatus").value = item?.status || "pending";
    if (item) {
      $("#financeDescription").value = item.description || "";
      $("#financeAmount").value = item.amount ?? "";
      $("#financeDue").value = item.due_date || "";
      $("#financeMethod").value = item.payment_method || "";
      $("#financeNotes").value = item.notes || "";
    }
    openDialog("financeDialog");
  }

  $("#financeForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = event.submitter;
    setBusy(button, true, "Salvando");
    const status = $("#financeStatus").value;
    const payload = {
      description: $("#financeDescription").value.trim(),
      entry_type: $("#financeType").value,
      amount: Number($("#financeAmount").value || 0),
      client_id: $("#financeClient").value || null,
      case_id: $("#financeCase").value || null,
      due_date: $("#financeDue").value || null,
      status,
      paid_at: status === "paid" ? new Date().toISOString() : null,
      payment_method: $("#financeMethod").value || null,
      notes: $("#financeNotes").value.trim() || null
    };
    try {
      if (state.editingFinanceId) {
        const { error } = await supabase.from("financial_entries").update(payload)
          .eq("id", state.editingFinanceId).eq("firm_id", state.firm.id);
        if (error) throw error;
        toast("Lançamento atualizado.");
      } else {
        const { error } = await supabase.from("financial_entries").insert({
          ...payload, firm_id: state.firm.id, created_by: state.user.id
        });
        if (error) throw error;
        toast("Lançamento criado.");
      }
      closeDialog("financeDialog");
      await core.loadData();
    } catch (error) {
      setStatus($("#financeStatusMsg"), error.message || "Não foi possível salvar o lançamento.", "err");
    } finally {
      setBusy(button, false);
    }
  });

  async function markPaid(id, button) {
    setBusy(button, true, "Baixando");
    try {
      const { error } = await supabase.from("financial_entries")
        .update({ status: "paid", paid_at: new Date().toISOString() })
        .eq("id", id).eq("firm_id", state.firm.id);
      if (error) throw error;
      toast("Pagamento registrado.");
      await core.loadData();
    } catch (error) {
      toast(error.message || "Não foi possível dar baixa.", "err");
    } finally {
      setBusy(button, false);
    }
  }

  function openLeadDialog(id = null) {
    state.editingLeadId = id;
    $("#leadForm").reset();
    setStatus($("#leadStatusMsg"));
    const item = id ? state.leads.find((x) => x.id === id) : null;
    $("#leadDialogTitle").textContent = item ? "Editar interessado" : "Novo interessado";
    $("#leadSaveBtn").textContent = item ? "Salvar alterações" : "Salvar interessado";
    $("#leadStage").value = item?.status || "lead";
    if (item) {
      $("#leadName").value = item.full_name || "";
      $("#leadPhone").value = item.phone || "";
      $("#leadEmail").value = item.email || "";
      $("#leadSource").value = item.source || "";
      $("#leadArea").value = item.legal_area || "";
      $("#leadValue").value = item.potential_value ?? "";
      $("#leadNextContact").value = toLocalInput(item.next_contact_at);
      $("#leadNotes").value = item.notes || "";
    }
    openDialog("leadDialog");
  }

  $("#leadForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = event.submitter;
    setBusy(button, true, "Salvando");
    const payload = {
      full_name: $("#leadName").value.trim(),
      phone: $("#leadPhone").value.trim() || null,
      email: $("#leadEmail").value.trim() || null,
      source: $("#leadSource").value.trim() || null,
      legal_area: $("#leadArea").value.trim() || null,
      status: $("#leadStage").value,
      potential_value: $("#leadValue").value ? Number($("#leadValue").value) : null,
      next_contact_at: toIso($("#leadNextContact").value),
      notes: $("#leadNotes").value.trim() || null
    };
    try {
      if (state.editingLeadId) {
        const { error } = await supabase.from("crm_leads").update(payload)
          .eq("id", state.editingLeadId).eq("firm_id", state.firm.id);
        if (error) throw error;
        toast("Interessado atualizado.");
      } else {
        const { error } = await supabase.from("crm_leads").insert({
          ...payload, firm_id: state.firm.id, created_by: state.user.id
        });
        if (error) throw error;
        toast("Interessado cadastrado.");
      }
      closeDialog("leadDialog");
      await core.loadData();
    } catch (error) {
      setStatus($("#leadStatusMsg"), error.message || "Não foi possível salvar o interessado.", "err");
    } finally {
      setBusy(button, false);
    }
  });

  function renderDeadlines() {
    const query = norm($("#deadlineSearch")?.value || "");
    const filter = $("#deadlineFilter")?.value || "open";
    const list = state.deadlines.filter((item) => {
      const client = clientById(item.client_id);
      const proc = caseById(item.case_id);
      const matchesQuery = norm(item.title + " " + (item.source || "") + " " + (client?.full_name || "") + " " + (proc?.process_number || "") + " " + (proc?.title || "")).includes(query);
      let matchesFilter = true;
      if (filter === "open") matchesFilter = !["completed","cancelled"].includes(item.status);
      if (filter === "overdue") matchesFilter = isOverdue(item.due_at, item.status);
      if (filter === "today") matchesFilter = isToday(item.due_at) && !["completed","cancelled"].includes(item.status);
      if (filter === "completed") matchesFilter = item.status === "completed";
      return matchesQuery && matchesFilter;
    });

    $("#deadlineCount").textContent = list.length + " de " + state.deadlines.length;
    $("#deadlinesBody").innerHTML = list.length ? list.map((item) => {
      const client = clientById(item.client_id);
      const proc = caseById(item.case_id);
      const statusType = item.status === "completed" ? "ok" : isOverdue(item.due_at, item.status) ? "err" : item.status === "in_progress" ? "warn" : "";
      return '<tr>' +
        '<td><div class="row-main"><strong>' + esc(item.title) + '</strong><span class="small">' + esc(item.source || "") + '</span></div></td>' +
        '<td><strong>' + esc(client?.full_name || "—") + '</strong><div class="small">' + esc(proc?.process_number || proc?.title || "") + '</div></td>' +
        '<td class="' + dueClass(item.due_at, item.status) + '">' + brDate(item.due_at) + '</td>' +
        '<td>' + badge(priorityLabels[item.priority] || item.priority, priorityTypes[item.priority] || "") + '</td>' +
        '<td>' + badge(deadlineStatusLabels[item.status] || item.status, statusType) + '</td>' +
        '<td><div class="row-actions">' +
          (!["completed","cancelled"].includes(item.status) ? '<button class="btn ghost sm" data-complete-deadline="' + item.id + '">Concluir</button>' : "") +
          '<button class="btn secondary sm" data-edit-deadline="' + item.id + '">Editar</button>' +
        '</div></td></tr>';
    }).join("") : '<tr><td colspan="6" class="empty">Nenhum prazo encontrado.</td></tr>';

    $$("[data-edit-deadline]").forEach((b) => b.addEventListener("click", () => openDeadlineDialog(b.dataset.editDeadline)));
    $$("[data-complete-deadline]").forEach((b) => b.addEventListener("click", () => completeDeadline(b.dataset.completeDeadline, b)));
  }

  function renderTasks() {
    const query = norm($("#taskSearch")?.value || "");
    const filter = $("#taskFilter")?.value || "open";
    const list = state.tasks.filter((item) => {
      const client = clientById(item.client_id);
      const proc = caseById(item.case_id);
      const matchesQuery = norm(item.title + " " + (item.description || "") + " " + (client?.full_name || "") + " " + (proc?.process_number || "")).includes(query);
      let matchesFilter = true;
      if (filter === "open") matchesFilter = !["completed","cancelled"].includes(item.status);
      if (filter === "overdue") matchesFilter = isOverdue(item.due_at, item.status);
      if (filter === "completed") matchesFilter = item.status === "completed";
      return matchesQuery && matchesFilter;
    });

    $("#taskCount").textContent = list.length + " de " + state.tasks.length;
    $("#tasksBody").innerHTML = list.length ? list.map((item) => {
      const client = clientById(item.client_id);
      const proc = caseById(item.case_id);
      const statusType = item.status === "completed" ? "ok" : isOverdue(item.due_at, item.status) ? "err" : item.status === "in_progress" ? "warn" : "";
      return '<tr>' +
        '<td><div class="row-main"><strong>' + esc(item.title) + '</strong><span class="small">' + esc(item.description || "") + '</span></div></td>' +
        '<td><strong>' + esc(client?.full_name || "—") + '</strong><div class="small">' + esc(proc?.process_number || proc?.title || "") + '</div></td>' +
        '<td class="' + dueClass(item.due_at, item.status) + '">' + (item.due_at ? brDate(item.due_at) : "—") + '</td>' +
        '<td>' + badge(priorityLabels[item.priority] || item.priority, priorityTypes[item.priority] || "") + '</td>' +
        '<td>' + badge(taskStatusLabels[item.status] || item.status, statusType) + '</td>' +
        '<td><div class="row-actions">' +
          (!["completed","cancelled"].includes(item.status) ? '<button class="btn ghost sm" data-complete-task="' + item.id + '">Concluir</button>' : "") +
          '<button class="btn secondary sm" data-edit-task="' + item.id + '">Editar</button>' +
        '</div></td></tr>';
    }).join("") : '<tr><td colspan="6" class="empty">Nenhuma tarefa encontrada.</td></tr>';

    $$("[data-edit-task]").forEach((b) => b.addEventListener("click", () => openTaskDialog(b.dataset.editTask)));
    $$("[data-complete-task]").forEach((b) => b.addEventListener("click", () => completeTask(b.dataset.completeTask, b)));
  }

  function renderAgenda() {
    const filter = $("#agendaFilter")?.value || "upcoming";
    const now = Date.now();
    const list = state.calendarEvents.filter((item) => {
      if (filter === "today") return isToday(item.start_at);
      if (filter === "upcoming") return new Date(item.start_at).getTime() >= now - 3600000;
      return true;
    }).sort((a,b) => new Date(a.start_at) - new Date(b.start_at));

    $("#agendaCount").textContent = list.length + " compromissos";
    $("#agendaList").innerHTML = list.length ? list.map((item) => {
      const client = clientById(item.client_id);
      const proc = caseById(item.case_id);
      const cls = isToday(item.start_at) ? "event today" : "event";
      const detail = [client?.full_name, proc?.process_number || proc?.title, item.location].filter(Boolean).join(" • ");
      return '<div class="' + cls + '">' +
        '<div style="display:flex;justify-content:space-between;gap:12px;align-items:flex-start;flex-wrap:wrap">' +
          '<div><strong>' + esc(item.title) + '</strong><small>' + esc(eventTypeLabels[item.event_type] || item.event_type) + ' • ' + brDate(item.start_at) + '</small></div>' +
          '<button class="btn secondary sm" data-edit-event="' + item.id + '">Editar</button>' +
        '</div>' +
        (detail ? '<p>' + esc(detail) + '</p>' : "") +
      '</div>';
    }).join("") : '<div class="empty">Nenhum compromisso encontrado.</div>';

    $$("[data-edit-event]").forEach((b) => b.addEventListener("click", () => openEventDialog(b.dataset.editEvent)));
  }

  function effectiveFinanceStatus(item) {
    if (item.status === "pending" && item.due_date) {
      const due = item.due_date + "T23:59:59";
      if (new Date(due).getTime() < Date.now() && !isToday(due)) return "overdue";
    }
    return item.status;
  }

  function renderFinance() {
    const query = norm($("#financeSearch")?.value || "");
    const filter = $("#financeFilter")?.value || "all";

    const receivable = (item) => !payableTypes.has(item.entry_type);
    const pending = state.financialEntries.filter((x) => receivable(x) && effectiveFinanceStatus(x) === "pending").reduce((a,x) => a + Number(x.amount || 0), 0);
    const paid = state.financialEntries.filter((x) => receivable(x) && x.status === "paid").reduce((a,x) => a + Number(x.amount || 0), 0);
    const overdue = state.financialEntries.filter((x) => receivable(x) && effectiveFinanceStatus(x) === "overdue").reduce((a,x) => a + Number(x.amount || 0), 0);
    $("#fPending").textContent = money(pending);
    $("#fPaid").textContent = money(paid);
    $("#fOverdue").textContent = money(overdue);

    const list = state.financialEntries.filter((item) => {
      const client = clientById(item.client_id);
      const status = effectiveFinanceStatus(item);
      const matchesFilter = filter === "all" || status === filter;
      const matchesQuery = norm(item.description + " " + (client?.full_name || "") + " " + (financeTypeLabels[item.entry_type] || "")).includes(query);
      return matchesFilter && matchesQuery;
    });

    $("#financeCount").textContent = list.length + " de " + state.financialEntries.length;
    $("#financeBody").innerHTML = list.length ? list.map((item) => {
      const client = clientById(item.client_id);
      const status = effectiveFinanceStatus(item);
      const statusType = status === "paid" ? "ok" : status === "overdue" ? "err" : status === "pending" ? "warn" : "";
      const payable = payableTypes.has(item.entry_type);
      return '<tr>' +
        '<td><strong>' + esc(item.description) + '</strong><div class="small">' + esc(financeTypeLabels[item.entry_type] || item.entry_type) + '</div></td>' +
        '<td>' + esc(client?.full_name || "—") + '</td>' +
        '<td class="' + (payable ? "money-negative" : "money-positive") + '">' + (payable ? "− " : "") + money(item.amount) + '</td>' +
        '<td>' + esc(item.due_date ? new Intl.DateTimeFormat("pt-BR").format(new Date(item.due_date + "T12:00:00")) : "—") + '</td>' +
        '<td>' + badge(financeStatusLabels[status] || status, statusType) + '</td>' +
        '<td><div class="row-actions">' +
          (status !== "paid" && status !== "cancelled" ? '<button class="btn ghost sm" data-pay-finance="' + item.id + '">Dar baixa</button>' : "") +
          '<button class="btn secondary sm" data-edit-finance="' + item.id + '">Editar</button>' +
        '</div></td></tr>';
    }).join("") : '<tr><td colspan="6" class="empty">Nenhum lançamento encontrado.</td></tr>';

    $$("[data-edit-finance]").forEach((b) => b.addEventListener("click", () => openFinanceDialog(b.dataset.editFinance)));
    $$("[data-pay-finance]").forEach((b) => b.addEventListener("click", () => markPaid(b.dataset.payFinance, b)));
  }

  function renderLeads() {
    const query = norm($("#leadSearch")?.value || "");
    const filter = $("#leadFilter")?.value || "all";
    const list = state.leads.filter((item) => {
      const matchesFilter = filter === "all" || item.status === filter;
      const matchesQuery = norm(item.full_name + " " + (item.phone || "") + " " + (item.email || "") + " " + (item.source || "") + " " + (item.legal_area || "")).includes(query);
      return matchesFilter && matchesQuery;
    });
    $("#leadCount").textContent = list.length + " de " + state.leads.length;
    $("#leadsBody").innerHTML = list.length ? list.map((item) => {
      const statusType = item.status === "contracted" ? "ok" : item.status === "lost" ? "err" : item.status === "proposal" ? "warn" : "";
      return '<tr>' +
        '<td><strong>' + esc(item.full_name) + '</strong><div class="small">' + esc(item.phone || item.email || "") + '</div></td>' +
        '<td>' + esc([item.source, item.legal_area].filter(Boolean).join(" • ") || "—") + '</td>' +
        '<td>' + (item.next_contact_at ? brDate(item.next_contact_at) : "—") + '</td>' +
        '<td>' + badge(leadStatusLabels[item.status] || item.status, statusType) + '</td>' +
        '<td>' + (item.potential_value != null ? money(item.potential_value) : "—") + '</td>' +
        '<td><button class="btn secondary sm" data-edit-lead="' + item.id + '">Editar</button></td>' +
      '</tr>';
    }).join("") : '<tr><td colspan="6" class="empty">Nenhum interessado encontrado.</td></tr>';
    $$("[data-edit-lead]").forEach((b) => b.addEventListener("click", () => openLeadDialog(b.dataset.editLead)));
  }

  function renderToday() {
    const openDeadlines = state.deadlines.filter((x) => !["completed","cancelled"].includes(x.status));
    const openTasks = state.tasks.filter((x) => !["completed","cancelled"].includes(x.status));
    $("#mDeadlines").textContent = openDeadlines.length;
    $("#mTasks").textContent = openTasks.length;

    const items = [];
    openDeadlines.filter((x) => isOverdue(x.due_at, x.status)).slice(0,4).forEach((x) => {
      items.push('<div class="event urgent"><strong>Prazo vencido: ' + esc(x.title) + '</strong><small>' + brDate(x.due_at) + '</small></div>');
    });
    openDeadlines.filter((x) => isToday(x.due_at)).slice(0,4).forEach((x) => {
      items.push('<div class="event today"><strong>Prazo hoje: ' + esc(x.title) + '</strong><small>' + brDate(x.due_at) + '</small></div>');
    });
    openTasks.filter((x) => isOverdue(x.due_at, x.status)).slice(0,3).forEach((x) => {
      items.push('<div class="event urgent"><strong>Tarefa atrasada: ' + esc(x.title) + '</strong><small>' + (x.due_at ? brDate(x.due_at) : "") + '</small></div>');
    });
    state.calendarEvents.filter((x) => isToday(x.start_at)).slice(0,4).forEach((x) => {
      items.push('<div class="event today"><strong>' + esc(eventTypeLabels[x.event_type] || "Compromisso") + ': ' + esc(x.title) + '</strong><small>' + brDate(x.start_at) + '</small></div>');
    });

    $("#todayList").innerHTML = items.length ? items.join("") : '<div class="event ok"><strong>Agenda operacional em dia</strong><small>Nenhum prazo vencido, tarefa atrasada ou compromisso para hoje.</small></div>';
  }

  function renderAll() {
    fillOrganizerSelects();
    renderDeadlines();
    renderTasks();
    renderAgenda();
    renderFinance();
    renderLeads();
    renderToday();
  }

  async function loadData(firmId) {
    const [deadlines, tasks, events, finance, leads] = await Promise.all([
      supabase.from("deadlines").select("*").eq("firm_id", firmId).order("due_at", { ascending: true }),
      supabase.from("tasks").select("*").eq("firm_id", firmId).order("due_at", { ascending: true, nullsFirst: false }),
      supabase.from("calendar_events").select("*").eq("firm_id", firmId).order("start_at", { ascending: true }),
      supabase.from("financial_entries").select("*").eq("firm_id", firmId).order("created_at", { ascending: false }),
      supabase.from("crm_leads").select("*").eq("firm_id", firmId).order("updated_at", { ascending: false })
    ]);
    const errors = [deadlines.error, tasks.error, events.error, finance.error, leads.error].filter(Boolean);
    if (errors.length) throw errors[0];
    state.deadlines = deadlines.data || [];
    state.tasks = tasks.data || [];
    state.calendarEvents = events.data || [];
    state.financialEntries = finance.data || [];
    state.leads = leads.data || [];
  }

  $("#newDeadlineBtn")?.addEventListener("click", () => openDeadlineDialog());
  $("#quickDeadline")?.addEventListener("click", () => openDeadlineDialog());
  $("#newTaskBtn")?.addEventListener("click", () => openTaskDialog());
  $("#quickTask")?.addEventListener("click", () => openTaskDialog());
  $("#newEventBtn")?.addEventListener("click", () => openEventDialog());
  $("#newFinanceBtn")?.addEventListener("click", () => openFinanceDialog());
  $("#newLeadBtn")?.addEventListener("click", () => openLeadDialog());

  ["deadlineSearch","deadlineFilter"].forEach((id) => $("#" + id)?.addEventListener("input", renderDeadlines));
  ["taskSearch","taskFilter"].forEach((id) => $("#" + id)?.addEventListener("input", renderTasks));
  $("#agendaFilter")?.addEventListener("input", renderAgenda);
  ["financeSearch","financeFilter"].forEach((id) => $("#" + id)?.addEventListener("input", renderFinance));
  ["leadSearch","leadFilter"].forEach((id) => $("#" + id)?.addEventListener("input", renderLeads));

  window.AdvogaOrganizer = { loadData, renderAll };
})();