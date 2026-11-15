// Realtime DevBoard web client. All user content is rendered with textContent / value (never innerHTML).

type ElementType = 'TEXT' | 'SHAPE' | 'CODE' | 'ARROW';

interface ExecutionResult {
    status: string;
    stdout: string;
    stderr: string;
    exitCode: number | null;
    durationMs: number;
    message: string;
}

interface BoardElement {
    id: number;
    type: ElementType;
    x: number;
    y: number;
    width: number;
    height: number;
    content: string | null;
    shape: string | null;
    endX: number | null;
    endY: number | null;
    startId: number | null;
    endId: number | null;
    lastRun: ExecutionResult | null;
}

interface BoardView {
    id: number;
    name: string;
    workspaceId: number;
    workspaceName: string;
    elements: BoardElement[];
}

interface WorkspaceView {
    id: number;
    name: string;
    boards: { id: number; name: string }[];
}

type BoardEvent = { type: 'upsert'; element: BoardElement } | { type: 'delete'; id: number };

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const boardEl = $<HTMLElement>('board');
const errorEl = $<HTMLElement>('error');
const select = $<HTMLSelectElement>('board-select');

let current: BoardView | null = null;
let socket: WebSocket | null = null;
const elements = new Map<number, BoardElement>();
const nodes = new Map<number, HTMLElement>();
const pendingSaves = new Map<number, number>();
const running = new Set<number>(); // code blocks this client is running right now

function csrfToken(): string {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
}

async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response = await fetch(path, {
        method,
        headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken()},
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (response.status === 401) {
        location.href = '/login';
    }
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`${response.status} ${text.slice(0, 200)}`);
    }
    return (response.status === 204 ? undefined : await response.json()) as T;
}

function showError(e: unknown) {
    errorEl.textContent = e instanceof Error ? e.message : String(e);
}

// ---------- rendering ----------

function render(el: BoardElement) {
    elements.set(el.id, el);
    let node = nodes.get(el.id);
    if (!node) {
        node = createNode(el);
        nodes.set(el.id, node);
        boardEl.append(node);
    }
    if (el.type === 'ARROW') {
        renderArrow(node, el);
        return;
    }
    Object.assign(node.style, {left: `${el.x}px`, top: `${el.y}px`, width: `${el.width}px`, height: `${el.height}px`});
    const text = node.querySelector('textarea');
    // Last write wins per element, but do not overwrite what the local user is typing right now.
    if (text && document.activeElement !== text) {
        text.value = el.content ?? '';
    }
    if (el.type === 'SHAPE') {
        node.classList.toggle('CIRCLE', el.shape === 'CIRCLE');
    }
    if (el.type === 'CODE') {
        // While running, socket echoes still carry the previous result; keep showing RUNNING instead.
        if (!running.has(el.id)) renderConsole(node, el.lastRun);
    }
    attachedArrows(el.id).forEach(a => renderArrow(nodes.get(a.id)!, a));
}

function createNode(el: BoardElement): HTMLElement {
    const node = document.createElement('div');
    node.className = `el ${el.type}`;
    node.dataset.id = String(el.id);

    if (el.type === 'ARROW') {
        return createArrow(el, node);
    }
    if (el.type === 'SHAPE') {
        // Pressing the shape drags it; double-click (or Tab focus) edits the label inside.
        node.setAttribute('role', 'group');
        node.setAttribute('aria-label', el.shape === 'CIRCLE' ? 'Apskritimas' : 'Stačiakampis');
        const label = document.createElement('textarea');
        label.className = 'label';
        label.setAttribute('aria-label', 'Figūros tekstas');
        label.addEventListener('input', () => scheduleSave(el.id, {content: label.value}));
        label.addEventListener('focus', () => label.classList.add('editing'));
        label.addEventListener('blur', () => label.classList.remove('editing'));
        node.addEventListener('dblclick', () => label.focus());
        node.append(label, button('×', 'delete', () => remove(el.id), 'Pašalinti figūrą'), resizeHandle(el.id));
        enableDrag(node, el.id, 'move');
        return node;
    }

    const bar = document.createElement('div');
    bar.className = 'bar';
    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = el.type === 'CODE' ? 'Java' : 'Tekstas';
    bar.append(title);
    if (el.type === 'CODE') {
        bar.append(button('▶ Run', 'run', () => run(el.id)));
    }
    bar.append(button('×', 'delete', () => remove(el.id), 'Pašalinti'));
    enableDrag(bar, el.id, 'move');

    const text = document.createElement('textarea');
    text.spellcheck = el.type !== 'CODE';
    text.setAttribute('aria-label', title.textContent);
    text.addEventListener('input', () => scheduleSave(el.id, {content: text.value}));
    node.append(bar, text);

    if (el.type === 'CODE') {
        text.addEventListener('keydown', e => {
            if (e.key === 'Tab') {
                e.preventDefault();
                text.setRangeText('    ', text.selectionStart, text.selectionEnd, 'end');
                scheduleSave(el.id, {content: text.value});
            }
        });
        const console = document.createElement('div');
        console.className = 'console';
        console.setAttribute('aria-live', 'polite');
        node.append(console);
    }
    node.append(resizeHandle(el.id));
    return node;
}

function resizeHandle(id: number): HTMLElement {
    const handle = document.createElement('div');
    handle.className = 'resize';
    enableDrag(handle, id, 'resize');
    return handle;
}

const SVG = 'http://www.w3.org/2000/svg';
const ARROW_PAD = 12; // room around the line for the head and the end handles

function svg<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string>): SVGElementTagNameMap[K] {
    const e = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    return e;
}

/** A free arrow from (x, y) to (endX, endY): drag the line to move it, drag an end to reshape it. */
function createArrow(el: BoardElement, node: HTMLElement): HTMLElement {
    node.setAttribute('role', 'img');
    node.setAttribute('aria-label', 'Rodyklė');
    const marker = svg('marker', {id: `head-${el.id}`, viewBox: '0 0 10 10', refX: '9', refY: '5',
        markerWidth: '8', markerHeight: '8', orient: 'auto'});
    marker.append(svg('path', {d: 'M0,0 L10,5 L0,10 z'}));
    const defs = svg('defs', {});
    defs.append(marker);
    const line = svg('line', {class: 'line', 'marker-end': `url(#head-${el.id})`});
    const hit = svg('line', {class: 'hit'});
    const canvas = svg('svg', {});
    canvas.append(defs, line, hit);
    const start = document.createElement('div');
    start.className = 'handle start';
    const end = document.createElement('div');
    end.className = 'handle end';
    node.append(canvas, start, end, button('×', 'delete', () => remove(el.id), 'Pašalinti rodyklę'));
    enableDrag(hit, el.id, 'move');
    enableDrag(start, el.id, 'start');
    enableDrag(end, el.id, 'end');
    return node;
}

function renderArrow(node: HTMLElement, el: BoardElement) {
    const p = arrowPoints(el);
    const left = Math.min(p.x1, p.x2) - ARROW_PAD, top = Math.min(p.y1, p.y2) - ARROW_PAD;
    Object.assign(node.style, {left: `${left}px`, top: `${top}px`,
        width: `${Math.abs(p.x2 - p.x1) + 2 * ARROW_PAD}px`, height: `${Math.abs(p.y2 - p.y1) + 2 * ARROW_PAD}px`});
    const points = {x1: p.x1 - left, y1: p.y1 - top, x2: p.x2 - left, y2: p.y2 - top};
    node.querySelectorAll('line').forEach(l => {
        for (const [k, v] of Object.entries(points)) l.setAttribute(k, String(v));
    });
    const place = (selector: string, x: number, y: number) =>
        Object.assign(node.querySelector<HTMLElement>(selector)!.style, {left: `${x}px`, top: `${y}px`});
    place('.start', points.x1, points.y1);
    place('.end', points.x2, points.y2);
    place('.delete', (points.x1 + points.x2) / 2, (points.y1 + points.y2) / 2);
}

function button(label: string, className: string, onClick: () => void, ariaLabel?: string): HTMLButtonElement {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = className;
    b.textContent = label;
    if (ariaLabel) b.setAttribute('aria-label', ariaLabel);
    b.addEventListener('click', onClick);
    return b;
}

function renderConsole(node: HTMLElement, result: ExecutionResult | null | 'RUNNING') {
    const console = node.querySelector<HTMLElement>('.console')!;
    console.replaceChildren();
    const status = document.createElement('div');
    status.className = 'status';
    if (result === null) {
        status.textContent = 'Execution Console: dar nevykdyta';
    } else if (result === 'RUNNING') {
        status.classList.add('RUNNING');
        status.textContent = 'RUNNING…';
    } else {
        status.classList.add(result.status === 'SUCCESS' ? 'SUCCESS' : 'FAILED');
        status.textContent = `${result.status} · ${result.message} · ${result.durationMs} ms`;
    }
    console.append(status);
    if (result && result !== 'RUNNING') {
        for (const [cls, text] of [['stdout', result.stdout], ['stderr', result.stderr]]) {
            if (text) {
                const pre = document.createElement('pre');
                pre.className = cls;
                pre.textContent = text;
                console.append(pre);
            }
        }
    }
}

// ---------- arrow attachment ----------

type Point = { x: number; y: number };

function anchorOf(id: number | null): BoardElement | undefined {
    const target = id == null ? undefined : elements.get(id);
    return target && target.type !== 'ARROW' ? target : undefined; // deleted target = free end
}

function attachedArrows(id: number): BoardElement[] {
    return [...elements.values()].filter(a => a.type === 'ARROW' && (a.startId === id || a.endId === id));
}

const centerOf = (e: BoardElement): Point => ({x: e.x + e.width / 2, y: e.y + e.height / 2});

/** Where the line from e's center towards `toward` crosses e's outline (ellipse for circles, else rectangle). */
function borderPoint(e: BoardElement, toward: Point): Point {
    const c = centerOf(e), dx = toward.x - c.x, dy = toward.y - c.y;
    if (dx === 0 && dy === 0) return c;
    const hw = e.width / 2, hh = e.height / 2;
    const t = e.shape === 'CIRCLE'
        ? 1 / Math.hypot(dx / hw, dy / hh)
        : Math.min(dx ? hw / Math.abs(dx) : Infinity, dy ? hh / Math.abs(dy) : Infinity);
    return {x: c.x + dx * t, y: c.y + dy * t};
}

/** Attached ends follow their element; free ends use the stored coordinates. */
function arrowPoints(el: BoardElement) {
    const a = anchorOf(el.startId), b = anchorOf(el.endId);
    const p1 = a ? centerOf(a) : {x: el.x, y: el.y};
    const p2 = b ? centerOf(b) : {x: el.endX ?? el.x, y: el.endY ?? el.y};
    const s = a ? borderPoint(a, p2) : p1, t = b ? borderPoint(b, p1) : p2;
    return {x1: s.x, y1: s.y, x2: t.x, y2: t.y};
}

/** Store the currently drawn end points, so a free (or later detached) end stays where it is seen. */
function freezeArrow(el: BoardElement) {
    const p = arrowPoints(el);
    el.x = clamp(p.x1, 0, 10_000);
    el.y = clamp(p.y1, 0, 10_000);
    el.endX = clamp(p.x2, 0, 10_000);
    el.endY = clamp(p.y2, 0, 10_000);
}

/** The non-arrow element under the pointer, if any (the dragged handle itself is skipped). */
function dropTargetAt(e: PointerEvent, arrowId: number): BoardElement | undefined {
    for (const hit of document.elementsFromPoint(e.clientX, e.clientY)) {
        const node = hit.closest<HTMLElement>('.el:not(.ARROW)');
        const id = Number(node?.dataset.id);
        if (node && id !== arrowId) return elements.get(id);
    }
    return undefined;
}

function highlightDropTarget(target: BoardElement | undefined) {
    document.querySelectorAll('.drop-target').forEach(n => n.classList.remove('drop-target'));
    if (target) nodes.get(target.id)?.classList.add('drop-target');
}

// ---------- editing ----------

// Same bounds as the server-side validation (BoardController.ElementRequest).
const clamp = (v: number, min: number, max: number) => Math.min(max, Math.max(min, Math.round(v)));

function enableDrag(target: HTMLElement | SVGElement, id: number, mode: 'move' | 'resize' | 'start' | 'end') {
    const handle = target as HTMLElement; // SVG elements have the same pointer-event API; TS cannot merge the union overloads
    handle.addEventListener('pointerdown', down => {
        const target = down.target as Element;
        if (target.closest('button, textarea.editing') || (mode === 'move' && target.closest('.resize'))) return;
        const el = elements.get(id)!;
        const isEnd = mode === 'start' || mode === 'end';
        if (el.type === 'ARROW') {
            // Dragged ends are detached; they re-attach on release over an element.
            freezeArrow(el);
            if (mode !== 'end') el.startId = null;
            if (mode !== 'start') el.endId = null;
        }
        const start = {x: el.x, y: el.y, width: el.width, height: el.height,
            endX: el.endX ?? 0, endY: el.endY ?? 0, cx: down.clientX, cy: down.clientY};
        handle.setPointerCapture(down.pointerId);
        const move = (e: PointerEvent) => {
            const dx = e.clientX - start.cx, dy = e.clientY - start.cy;
            if (mode === 'move' || mode === 'start') {
                el.x = clamp(start.x + dx, 0, 10_000);
                el.y = clamp(start.y + dy, 0, 10_000);
            }
            if ((mode === 'move' && el.type === 'ARROW') || mode === 'end') {
                el.endX = clamp(start.endX + dx, 0, 10_000);
                el.endY = clamp(start.endY + dy, 0, 10_000);
            }
            if (mode === 'resize') {
                el.width = clamp(start.width + dx, 20, 4_000);
                el.height = clamp(start.height + dy, 20, 4_000);
            }
            render(el);
            if (isEnd) highlightDropTarget(dropTargetAt(e, id));
        };
        const up = (e: PointerEvent) => {
            handle.removeEventListener('pointermove', move);
            handle.removeEventListener('pointerup', up);
            if (isEnd) {
                const target = dropTargetAt(e, id);
                highlightDropTarget(undefined);
                if (mode === 'start') el.startId = target?.id ?? null;
                else el.endId = target?.id ?? null;
                freezeArrow(el);
                render(el);
            }
            save(id).catch(showError);
            // keep attached arrows' stored points current (used if their target is deleted later)
            attachedArrows(id).forEach(a => {
                freezeArrow(a);
                save(a.id).catch(showError);
            });
        };
        handle.addEventListener('pointermove', move);
        handle.addEventListener('pointerup', up);
    });
}

function scheduleSave(id: number, change: Partial<BoardElement>) {
    Object.assign(elements.get(id)!, change);
    clearTimeout(pendingSaves.get(id));
    pendingSaves.set(id, window.setTimeout(() => save(id).catch(showError), 400));
}

async function save(id: number) {
    clearTimeout(pendingSaves.get(id));
    pendingSaves.delete(id);
    const el = elements.get(id);
    if (!el || !current) return;
    const {type, x, y, width, height, content, shape, endX, endY, startId, endId} = el;
    await api('PUT', `/api/boards/${current.id}/elements/${id}`,
        {type, x, y, width, height, content, shape, endX, endY, startId, endId});
}

async function add(type: ElementType, shape?: string) {
    if (!current) return;
    const size = type === 'CODE' ? {width: 460, height: 300}
        : shape === 'CIRCLE' ? {width: 120, height: 120} : {width: 200, height: 100};
    const content = type === 'CODE'
        ? 'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello");\n    }\n}\n'
        : '';
    const offset = 20 * (elements.size % 10);
    const x = 60 + offset, y = 60 + offset;
    const body = type === 'ARROW'
        ? {type, x, y, width: 20, height: 20, content, endX: x + 160, endY: y}
        : {type, x, y, ...size, content, shape: type === 'SHAPE' ? shape : null};
    render(await api<BoardElement>('POST', `/api/boards/${current.id}/elements`, body));
}

async function remove(id: number) {
    if (!current) return;
    await api('DELETE', `/api/boards/${current.id}/elements/${id}`).catch(showError);
    drop(id);
}

function drop(id: number) {
    const arrows = attachedArrows(id);
    nodes.get(id)?.remove();
    nodes.delete(id);
    elements.delete(id);
    arrows.forEach(a => renderArrow(nodes.get(a.id)!, a)); // now free at their last saved points
}

async function run(id: number) {
    const node = nodes.get(id);
    if (!current || !node) return;
    const runButton = node.querySelector<HTMLButtonElement>('.run')!;
    runButton.disabled = true;
    running.add(id);
    renderConsole(node, 'RUNNING');
    try {
        await save(id); // the server runs the saved code
        const result = await api<ExecutionResult>('POST', `/api/boards/${current.id}/elements/${id}/run`);
        elements.get(id)!.lastRun = result;
        renderConsole(node, result);
    } catch (e) {
        renderConsole(node, null);
        showError(e);
    } finally {
        running.delete(id);
        runButton.disabled = false;
    }
}

// ---------- boards and realtime ----------

async function openBoard(boardId: number) {
    socket?.close();
    socket = null;
    const board = await api<BoardView>('GET', `/api/boards/${boardId}`);
    current = board;
    location.hash = `board=${board.id}`;
    select.value = String(board.id);
    boardEl.replaceChildren();
    elements.clear();
    nodes.clear();
    board.elements.forEach(render);
    connect(board.id);
}

function connect(boardId: number) {
    const live = $<HTMLElement>('live');
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/boards/${boardId}`);
    socket = ws;
    ws.onopen = () => live.classList.add('on');
    ws.onmessage = message => {
        const event = JSON.parse(message.data) as BoardEvent;
        if (event.type === 'upsert') render(event.element);
        else drop(event.id);
    };
    ws.onclose = () => {
        live.classList.remove('on');
        // reconnect and resync if this is still the open board
        if (socket === ws) setTimeout(() => socket === ws && openBoard(boardId).catch(showError), 2000);
    };
}

async function loadBoards(selectId?: number) {
    const workspaces = await api<WorkspaceView[]>('GET', '/api/workspaces');
    select.replaceChildren();
    for (const ws of workspaces) {
        const group = document.createElement('optgroup');
        group.label = ws.name;
        group.dataset.workspaceId = String(ws.id);
        for (const b of ws.boards) {
            group.append(new Option(b.name, String(b.id)));
        }
        select.append(group);
    }
    const fromHash = Number(new URLSearchParams(location.hash.slice(1)).get('board'));
    const target = selectId ?? (fromHash || Number(select.options[0]?.value));
    if (target) await openBoard(target);
}

async function newBoard() {
    const group = select.selectedOptions[0]?.parentElement as HTMLOptGroupElement | undefined;
    const workspaceId = group?.dataset.workspaceId;
    if (!workspaceId) return;
    const name = prompt(`Naujos lentos pavadinimas (${group.label}):`);
    if (!name) return;
    const board = await api<{ id: number }>('POST', `/api/workspaces/${workspaceId}/boards`, {name});
    await loadBoards(board.id);
}

async function start() {
    const me = await api<{ username: string }>('GET', '/api/me');
    $<HTMLElement>('user').textContent = me.username;
    select.addEventListener('change', () => openBoard(Number(select.value)).catch(showError));
    $<HTMLButtonElement>('new-board').addEventListener('click', () => newBoard().catch(showError));
    document.querySelectorAll<HTMLButtonElement>('[data-add]').forEach(b =>
        b.addEventListener('click', () => add(b.dataset.add as ElementType, b.dataset.shape).catch(showError)));
    await loadBoards();
}

start().catch(showError);
