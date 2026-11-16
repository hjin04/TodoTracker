/**
 * TodoTracker — Google Apps Script 백엔드 (API 서버)
 * 앱 화면은 GitHub Pages / 안드로이드 앱에서 열리고, 이 스크립트와 '연결 코드'로 통신합니다.
 * 데이터: 내 구글 드라이브의 스프레드시트 "나의 할일 데이터"
 * 알림:   구글캘린더 "할일 알림" 캘린더의 일정 알림으로 휴대폰에 울림
 * 외출 등 '구글캘린더 연동' 카테고리: 기본 캘린더에 일정으로 등록
 */

var TABLES = ['categories', 'routines', 'tasks'];
var HEAD = ['id', 'date', 'key', 'updatedAt', 'deleted', 'json'];
var ALARM_CAL_NAME = '할일 알림';
var DEFAULT_CATS = [
  { name: '학교생활', color: '#4C7CF3' },
  { name: '과제', color: '#F25F5C' },
  { name: '공부', color: '#8B5CF6' },
  { name: '자기관리', color: '#22B07D' },
  { name: '외출', color: '#F59E0B', gcal: true },
  { name: '그외', color: '#8E8E93' }
];

/* ───────────────────────── 최초 설정 ───────────────────────── */

/** 처음 한 번, Apps Script 편집기에서 이 함수를 선택해 [실행]하세요. */
function setup() {
  var p = props_();
  var ss = null;
  var ssId = p.getProperty('SS_ID');
  if (ssId) { try { ss = SpreadsheetApp.openById(ssId); } catch (e) { ss = null; } }
  if (!ss) {
    ss = SpreadsheetApp.create('TodoTracker 데이터 (삭제 금지)');
    p.setProperty('SS_ID', ss.getId());
  }
  _ss = ss;
  TABLES.forEach(function (name) {
    var sh = ss.getSheetByName(name) || ss.insertSheet(name);
    sh.getRange(1, 1, sh.getMaxRows(), HEAD.length).setNumberFormat('@');
    sh.getRange(1, 1, 1, HEAD.length).setValues([HEAD]);
    sh.setFrozenRows(1);
  });
  ss.getSheets().forEach(function (sh) {
    if (TABLES.indexOf(sh.getName()) < 0) ss.deleteSheet(sh);
  });

  if (readRows_('categories').length === 0) {
    DEFAULT_CATS.forEach(function (c, i) {
      writeObj_('categories', { id: Utilities.getUuid(), name: c.name, color: c.color, gcal: !!c.gcal, order: i });
    });
  }
  alarmCal_();
  if (!p.getProperty('API_TOKEN')) p.setProperty('API_TOKEN', Utilities.getUuid().replace(/-/g, ''));

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'dailyJob') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('dailyJob').timeBased().everyDays(1).atHour(0).nearMinute(5).create();
  dailyJob();
  Logger.log('✅ 설정 완료! 데이터 시트: ' + ss.getUrl());
  Logger.log('🔑 연결 코드: ' + p.getProperty('API_TOKEN'));
}

/** 연결 코드를 잊었을 때 실행하면 다시 보여줌 */
function showConnectCode() {
  Logger.log('🔑 연결 코드: ' + props_().getProperty('API_TOKEN'));
}

/** 연결 코드가 유출됐을 때 실행 → 새 코드 발급 (모든 기기에서 다시 연결 필요) */
function resetConnectCode() {
  props_().setProperty('API_TOKEN', Utilities.getUuid().replace(/-/g, ''));
  showConnectCode();
}

/** 매일 0시쯤 자동 실행: 앞으로 7일치 '자동 추가' 루틴을 할일로 만들어 둠 */
function dailyJob() {
  withLock_(function () {
    var t = today_();
    if (getSettings_().autoCarry) carryOver_(t);
    generate_(t, addDays_(t, 6));
  });
}

/* ───────────────────────── 웹 API ───────────────────────── */

var API = {
  load: load, saveTask: saveTask, deleteTask: deleteTask, moveTasks: moveTasks,
  saveCategory: saveCategory, deleteCategory: deleteCategory, reorderCategories: reorderCategories,
  saveRoutine: saveRoutine, deleteRoutine: deleteRoutine, saveSettings: saveSettings,
  widget: widget, complete: complete, upcoming: upcoming
};

/** 앱 → POST { token, fn, args } */
function doPost(e) {
  var req;
  try { req = JSON.parse(e.postData.contents); } catch (err) { req = null; }
  return handle_(req);
}

/** 위젯 → GET ?token=..&fn=widget  /  ?token=..&fn=complete&id=.. */
function doGet(e) {
  var p = (e && e.parameter) || {};
  if (!p.fn) return ContentService.createTextOutput('✅ TodoTracker 서버가 동작 중이에요. 이 주소를 앱의 "서버 주소"에 붙여넣으세요.');
  return handle_({ token: p.token, fn: p.fn, args: p.id ? [p.id] : [] });
}

function handle_(req) {
  var out;
  try {
    var token = props_().getProperty('API_TOKEN');
    if (!token) throw new Error('서버 설정이 안 됐어요. Apps Script에서 setup을 먼저 실행해 주세요.');
    if (!req || req.token !== token) throw new Error('연결 코드가 올바르지 않아요.');
    var fn = API[req.fn];
    if (!fn) throw new Error('알 수 없는 요청: ' + req.fn);
    out = { ok: true, data: fn.apply(null, req.args || []) };
  } catch (err) {
    out = { ok: false, error: String((err && err.message) || err) };
  }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

/** 위젯용: 오늘 남은 할일 */
function widget() {
  var today = today_();
  var cats = catMap_();
  var tasks = listObjs_('tasks', function (r) { return r.date === today; }).filter(function (t) { return cats[t.categoryId]; });
  var left = tasks.filter(function (t) { return !t.done; }).sort(function (a, b) {
    return ((cats[a.categoryId].order || 0) - (cats[b.categoryId].order || 0)) ||
      (a.time || '~').localeCompare(b.time || '~') || ((a.createdAt || 0) - (b.createdAt || 0));
  });
  return {
    date: today, total: tasks.length, done: tasks.length - left.length,
    items: left.map(function (t) {
      var c = cats[t.categoryId];
      return { id: t.id, title: t.title, time: t.time || '', color: c.color, cat: c.name };
    })
  };
}

/** 위젯에서 체크 → 완료 처리 후 최신 위젯 데이터 반환 */
function complete(id) {
  withLock_(function () {
    var t = getObj_('tasks', id);
    if (t && !t.deleted && !t.done) {
      t.done = true;
      t.doneAt = Date.now();
      syncCalendar_(t);
      writeObj_('tasks', t);
    }
  });
  return widget();
}

/* ───────────────────────── 클라이언트 API ───────────────────────── */

function load(from, to) {
  return {
    today: today_(),
    from: from,
    to: to,
    categories: listObjs_('categories'),
    routines: listObjs_('routines'),
    settings: getSettings_(),
    tasks: listObjs_('tasks', function (r) { return r.date >= from && r.date <= to; })
  };
}

function saveTask(task) {
  return withLock_(function () {
    var old = getObj_('tasks', task.id);
    // 캘린더 일정 ID와 루틴 키는 서버에 저장된 값을 기준으로 함 (중복 일정 방지)
    task.gcalId = (old && old.gcalId) || '';
    task.alarmId = (old && old.alarmId) || '';
    if (old && old.key) task.key = old.key;
    else if (task.routineId && !task.key) task.key = task.routineId + '|' + task.date;
    task.deleted = false;
    syncCalendar_(task);
    writeObj_('tasks', task);
    return true;
  });
}

function deleteTask(id) {
  return withLock_(function () {
    var t = getObj_('tasks', id);
    if (!t) return true;
    t.deleted = true;
    syncCalendar_(t, null);
    writeObj_('tasks', t);
    return true;
  });
}

function saveCategory(cat) {
  return withLock_(function () {
    var old = getObj_('categories', cat.id);
    cat.deleted = false;
    writeObj_('categories', cat);
    if (old && (!!old.gcal !== !!cat.gcal || old.name !== cat.name)) resyncCategory_(cat);
    return true;
  });
}

function deleteCategory(id) {
  return withLock_(function () {
    var cat = getObj_('categories', id);
    if (!cat) return true;
    cat.deleted = true;
    writeObj_('categories', cat);
    var sh = sheet_('tasks');
    readRows_('tasks').forEach(function (row) {
      if (row.deleted) return;
      var t = parse_(row);
      if (!t || t.categoryId !== id) return;
      t.deleted = true;
      syncCalendar_(t, null);
      writeAt_(sh, row.row, t);
    });
    var rsh = sheet_('routines');
    readRows_('routines').forEach(function (row) {
      if (row.deleted) return;
      var r = parse_(row);
      if (!r || r.categoryId !== id) return;
      r.deleted = true;
      writeAt_(rsh, row.row, r);
    });
    return true;
  });
}

function reorderCategories(ids) {
  return withLock_(function () {
    var sh = sheet_('categories');
    readRows_('categories').forEach(function (row) {
      var c = parse_(row);
      if (!c) return;
      var i = ids.indexOf(c.id);
      if (i >= 0 && c.order !== i) { c.order = i; writeAt_(sh, row.row, c); }
    });
    return true;
  });
}

/** 여러 할일을 다른 날짜로 옮김 (못 한 일 넘기기) */
function moveTasks(ids, date) {
  return withLock_(function () {
    var cats = catMap_();
    var sh = sheet_('tasks');
    readRows_('tasks').forEach(function (row) {
      if (row.deleted || ids.indexOf(row.id) < 0) return;
      var t = parse_(row);
      if (!t || t.date === date) return;
      moveOne_(t, date, cats);
      writeAt_(sh, row.row, t);
    });
    return true;
  });
}

function saveSettings(s) {
  s = s || {};
  var before = getSettings_();
  var p = props_();
  p.setProperty('AUTO_CARRY', s.autoCarry ? '1' : '');
  p.setProperty('ALARM_VIA', s.alarmVia === 'app' ? 'app' : 'calendar');
  _settings = null;
  // 알림 방식이 바뀌면 앞으로의 할일 캘린더 알림을 다시 맞춤 (중복 알림 방지)
  if (before.alarmVia !== getSettings_().alarmVia) {
    withLock_(function () {
      var today = today_(), cats = catMap_(), sh = sheet_('tasks');
      readRows_('tasks').forEach(function (row) {
        if (row.deleted || row.date < today) return;
        var t = parse_(row);
        if (!t || !cats[t.categoryId]) return;
        syncCalendar_(t, cats[t.categoryId]);
        writeAt_(sh, row.row, t);
      });
    });
  }
  return true;
}

var _settings = null;
function getSettings_() {
  if (_settings) return _settings;
  var p = props_();
  _settings = { autoCarry: p.getProperty('AUTO_CARRY') === '1', alarmVia: p.getProperty('ALARM_VIA') === 'app' ? 'app' : 'calendar' };
  return _settings;
}

/** 휴대폰 잠금화면 알림용: 앞으로 8일 안의 미완료 할일 */
function upcoming() {
  var today = today_(), until = addDays_(today, 8), cats = catMap_();
  return listObjs_('tasks').filter(function (t) {
    if (t.done || !cats[t.categoryId]) return false;
    return (t.date >= today && t.date <= until) || (t.due && t.due >= today && t.due <= until);
  }).map(function (t) {
    return { id: t.id, title: t.title, date: t.date, time: t.time || '', alarm: hasVal_(t.alarm) ? t.alarm : '', due: t.due || '', cat: cats[t.categoryId].name };
  });
}

/** 지난 7일 동안 완료 못 한 할일(루틴 할일 제외)을 오늘로 옮김 */
function carryOver_(today) {
  var from = addDays_(today, -7);
  var cats = catMap_();
  var sh = sheet_('tasks');
  readRows_('tasks').forEach(function (row) {
    if (row.deleted || row.date < from || row.date >= today) return;
    var t = parse_(row);
    if (!t || t.done || t.routineId || !cats[t.categoryId]) return;
    moveOne_(t, today, cats);
    writeAt_(sh, row.row, t);
  });
}

function moveOne_(t, date, cats) {
  t.movedFrom = t.movedFrom || t.date;
  t.date = date;
  syncCalendar_(t, cats[t.categoryId] || null);
}

function catMap_() {
  var m = {};
  listObjs_('categories').forEach(function (c) { m[c.id] = c; });
  return m;
}

/**
 * 루틴 저장. '자동 추가'면 앞으로 7일치 할일을 바로 만들어 돌려줌
 * cleanup: 루틴을 고칠 때 새 설정에 맞지 않는(또는 자동→수동으로 바뀐) 오늘 이후 할일 처리
 *          'all' 모두 삭제 / 'undone' 미완료만 삭제 / 'none'(기본) 그대로 둠
 */
function saveRoutine(r, cleanup) {
  return withLock_(function () {
    var old = getObj_('routines', r.id);
    r.deleted = false;
    writeObj_('routines', r);
    if (old && (cleanup === 'all' || cleanup === 'undone')) {
      var today = today_(), sh = sheet_('tasks'), cats = catMap_();
      readRows_('tasks').forEach(function (row) {
        if (row.deleted || row.date < today) return;
        var t = parse_(row);
        if (!t || t.routineId !== r.id) return;
        if (cleanup === 'undone' && t.done) return;
        if (!((old.autoAdd && !r.autoAdd) || !routineMatches_(r, t.date))) return;
        t.deleted = true;
        syncCalendar_(t, cats[t.categoryId] || null);
        writeAt_(sh, row.row, t);
      });
    }
    if (!r.autoAdd) return [];
    var t = today_();
    return generate_(t, addDays_(t, 6), r.id);
  });
}

/** 루틴 삭제 + 오늘 이후의 완료 안 한 루틴 할일도 삭제 */
function deleteRoutine(id) {
  return withLock_(function () {
    var r = getObj_('routines', id);
    if (r) { r.deleted = true; writeObj_('routines', r); }
    var today = today_();
    var sh = sheet_('tasks');
    readRows_('tasks').forEach(function (row) {
      if (row.deleted || row.date < today) return;
      var t = parse_(row);
      if (!t || t.routineId !== id || t.done) return;
      t.deleted = true;
      syncCalendar_(t, null);
      writeAt_(sh, row.row, t);
    });
    return true;
  });
}

/* ───────────────────────── 루틴 ───────────────────────── */

function generate_(from, to, onlyId) {
  var routines = listObjs_('routines').filter(function (r) {
    return r.autoAdd && (!onlyId || r.id === onlyId);
  });
  if (!routines.length) return [];
  var cats = {};
  listObjs_('categories').forEach(function (c) { cats[c.id] = c; });
  var keys = {};
  readRows_('tasks').forEach(function (row) { if (row.key) keys[row.key] = 1; }); // 삭제한 것도 포함 → 다시 안 생김

  var created = [];
  for (var d = from; d <= to; d = addDays_(d, 1)) {
    for (var i = 0; i < routines.length; i++) {
      var r = routines[i];
      var k = r.id + '|' + d;
      if (!cats[r.categoryId] || keys[k] || !routineMatches_(r, d)) continue;
      keys[k] = 1;
      var t = {
        id: Utilities.getUuid(), title: r.title, categoryId: r.categoryId, date: d,
        time: r.time || '', endTime: r.endTime || '', alarm: hasVal_(r.alarm) ? r.alarm : '',
        memo: '', due: '', done: false, routineId: r.id, key: k, createdAt: Date.now(),
        gcalId: '', alarmId: ''
      };
      syncCalendar_(t, cats[r.categoryId]);
      created.push(t);
    }
  }
  appendObjs_('tasks', created);
  return created;
}

function routineMatches_(r, s) {
  if (r.start && s < r.start) return false;
  if (r.end && s > r.end) return false;
  var p = s.split('-').map(Number);
  switch (r.repeat) {
    case 'daily': return true;
    case 'weekly': return (r.days || []).indexOf(dow_(s)) >= 0;
    case 'monthly': return p[2] === Math.min(Number(r.monthDay) || 1, daysInMonth_(p[0], p[1]));
    case 'interval':
      var n = Math.max(1, Number(r.every) || 1);
      return ((dayNum_(s) - dayNum_(r.start || s)) % n) === 0;
  }
  return false;
}

/* ───────────────────────── 구글캘린더 ───────────────────────── */

/**
 * - 카테고리가 '구글캘린더 연동'이면 → 기본 캘린더에 일정 (알림 포함)
 * - 아니면, 시간+알림이 있는 미완료 할일 → '할일 알림' 캘린더에 일정 (휴대폰 알림용)
 */
function syncCalendar_(t, cat) {
  try {
    if (cat === undefined) cat = t.categoryId ? getObj_('categories', t.categoryId) : null;
    var alive = !t.deleted && cat && !cat.deleted && t.date;
    var useG = !!(alive && cat.gcal);
    var viaApp = getSettings_().alarmVia === 'app'; // 휴대폰 앱이 직접 알림 → 캘린더 알림은 만들지 않음
    var useA = !!(alive && !useG && !t.done && hasVal_(t.alarm) && !viaApp);
    var main = CalendarApp.getDefaultCalendar();

    if (useG) t.gcalId = putEvent_(main, t.gcalId, t, (t.done || viaApp) ? null : t.alarm, '', 60);
    else if (t.gcalId) { dropEvent_(main, t.gcalId); t.gcalId = ''; }

    if (useA) t.alarmId = putEvent_(alarmCal_(), t.alarmId, t, t.alarm, '[' + cat.name + '] ', 30);
    else if (t.alarmId) { dropEvent_(alarmCal_(), t.alarmId); t.alarmId = ''; }
  } catch (e) {
    console.error('캘린더 동기화 실패: ' + e);
  }
}

function putEvent_(cal, id, t, alarm, prefix, defMin) {
  var title = prefix + (t.title || '(제목 없음)');
  var tm = eventTimes_(t, defMin);
  var ev = null;
  if (id) {
    try {
      ev = cal.getEventById(id);
      if (ev) {
        ev.setTitle(title);
        if (tm.allDay) ev.setAllDayDate(tm.start); else ev.setTime(tm.start, tm.end);
      }
    } catch (e) { ev = null; }
  }
  if (!ev) ev = tm.allDay ? cal.createAllDayEvent(title, tm.start) : cal.createEvent(title, tm.start, tm.end);
  ev.setDescription(t.memo || '');
  ev.removeAllReminders();
  if (hasVal_(alarm)) ev.addPopupReminder(Number(alarm));
  return ev.getId();
}

function dropEvent_(cal, id) {
  try { var ev = cal.getEventById(id); if (ev) ev.deleteEvent(); } catch (e) { /* 이미 지워진 일정 */ }
}

/** 시간이 없으면 오전 8시~9시 (삼성 캘린더 기본값과 같게) */
function eventTimes_(t, defMin) {
  var p = t.date.split('-').map(Number);
  if (!t.time) return { allDay: false, start: new Date(p[0], p[1] - 1, p[2], 8, 0), end: new Date(p[0], p[1] - 1, p[2], 9, 0) };
  var a = t.time.split(':').map(Number);
  var start = new Date(p[0], p[1] - 1, p[2], a[0], a[1]);
  var end;
  if (t.endTime && t.endTime > t.time) {
    var b = t.endTime.split(':').map(Number);
    end = new Date(p[0], p[1] - 1, p[2], b[0], b[1]);
  } else {
    end = new Date(start.getTime() + defMin * 60000);
  }
  return { allDay: false, start: start, end: end };
}

var _alarmCal = null;
function alarmCal_() {
  if (_alarmCal) return _alarmCal;
  var p = props_();
  var id = p.getProperty('ALARM_CAL_ID');
  var cal = id ? CalendarApp.getCalendarById(id) : null;
  if (!cal) {
    var found = CalendarApp.getOwnedCalendarsByName(ALARM_CAL_NAME);
    cal = found.length ? found[0] : CalendarApp.createCalendar(ALARM_CAL_NAME, { color: CalendarApp.Color.TEAL });
    p.setProperty('ALARM_CAL_ID', cal.getId());
  }
  _alarmCal = cal;
  return cal;
}

function resyncCategory_(cat) {
  var today = today_();
  var sh = sheet_('tasks');
  readRows_('tasks').forEach(function (row) {
    if (row.deleted || row.date < today) return;
    var t = parse_(row);
    if (!t || t.categoryId !== cat.id) return;
    syncCalendar_(t, cat);
    writeAt_(sh, row.row, t);
  });
}

/* ───────────────────────── 시트 저장소 ───────────────────────── */

var _ss = null;
function ss_() {
  if (_ss) return _ss;
  var id = props_().getProperty('SS_ID');
  if (!id) throw new Error('아직 설정이 안 됐어요. Apps Script 편집기에서 setup 함수를 먼저 실행해 주세요.');
  _ss = SpreadsheetApp.openById(id);
  return _ss;
}
function sheet_(name) { return ss_().getSheetByName(name); }

function readRows_(name) {
  var sh = sheet_(name);
  var n = sh.getLastRow() - 1;
  if (n < 1) return [];
  var vals = sh.getRange(2, 1, n, HEAD.length).getValues();
  var out = [];
  for (var i = 0; i < vals.length; i++) {
    var v = vals[i];
    if (!v[0]) continue;
    out.push({
      row: i + 2, id: String(v[0]), date: normDate_(v[1]), key: String(v[2] || ''),
      deleted: String(v[4]) === '1', json: v[5]
    });
  }
  return out;
}

function parse_(row) { try { return JSON.parse(row.json); } catch (e) { return null; } }

function listObjs_(name, pred) {
  return readRows_(name)
    .filter(function (r) { return !r.deleted && (!pred || pred(r)); })
    .map(parse_)
    .filter(function (o) { return !!o; });
}

function rowFor_(o) {
  return [o.id, o.date || '', o.key || '', String(Date.now()), o.deleted ? '1' : '', JSON.stringify(o)];
}

function findRow_(sh, id) {
  var last = sh.getLastRow();
  if (last < 2) return 0;
  var f = sh.getRange(2, 1, last - 1, 1).createTextFinder(id).matchEntireCell(true).findNext();
  return f ? f.getRow() : 0;
}

function writeAt_(sh, row, o) {
  var rg = sh.getRange(row, 1, 1, HEAD.length);
  rg.setNumberFormat('@');
  rg.setValues([rowFor_(o)]);
}

function writeObj_(name, o) {
  var sh = sheet_(name);
  writeAt_(sh, findRow_(sh, o.id) || sh.getLastRow() + 1, o);
}

function appendObjs_(name, list) {
  if (!list.length) return;
  var sh = sheet_(name);
  var rg = sh.getRange(sh.getLastRow() + 1, 1, list.length, HEAD.length);
  rg.setNumberFormat('@');
  rg.setValues(list.map(rowFor_));
}

function getObj_(name, id) {
  if (!id) return null;
  var sh = sheet_(name);
  var row = findRow_(sh, id);
  if (!row) return null;
  try { return JSON.parse(sh.getRange(row, 1, 1, HEAD.length).getValues()[0][5]); } catch (e) { return null; }
}

function withLock_(fn) {
  var lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try { return fn(); } finally { lock.releaseLock(); }
}

/* ───────────────────────── 유틸 ───────────────────────── */

function props_() { return PropertiesService.getScriptProperties(); }
function tz_() { return Session.getScriptTimeZone() || 'Asia/Seoul'; }
function hasVal_(v) { return v !== null && v !== undefined && v !== ''; }
function pad_(n) { return (n < 10 ? '0' : '') + n; }
function ymd_(y, m, d) { return y + '-' + pad_(m) + '-' + pad_(d); }
function parts_(s) { return String(s).split('-').map(Number); }
function addDays_(s, n) {
  var p = parts_(s);
  var t = new Date(Date.UTC(p[0], p[1] - 1, p[2] + n));
  return ymd_(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate());
}
function dayNum_(s) { var p = parts_(s); return Math.round(Date.UTC(p[0], p[1] - 1, p[2]) / 86400000); }
function dow_(s) { var p = parts_(s); return new Date(Date.UTC(p[0], p[1] - 1, p[2])).getUTCDay(); }
function daysInMonth_(y, m) { return new Date(Date.UTC(y, m, 0)).getUTCDate(); }
function rangeFor_(ym) {
  var p = parts_(ym);
  return { from: addDays_(ym + '-01', -7), to: addDays_(ymd_(p[0], p[1], daysInMonth_(p[0], p[1])), 14) };
}
function today_() { return Utilities.formatDate(new Date(), tz_(), 'yyyy-MM-dd'); }
function normDate_(v) {
  if (v instanceof Date) return Utilities.formatDate(v, tz_(), 'yyyy-MM-dd');
  return String(v || '');
}
