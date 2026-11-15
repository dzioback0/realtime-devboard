// Realtime DevBoard web client. All user content is rendered with textContent / value (never innerHTML).

type ElementType = 'TEXT' | 'SHAPE' | 'CODE';

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
    Object.assign(node.style, {left: `${el.x}px`, top: `${el.y}px`, width: `${el.width}px`, height: `${el.height}px`});
    const text = node.querySelector('textarea')!;
    // Last write wins per element, but do not overwrite what the local user is typing right now.
    if (document.activeElement !== text) {
        text.value = el.content ?? '';
    }
    if (el.type === 'CODE') {
        renderConsole(node, el.lastRun);
    }
}

function createNode(el: BoardElement): HTMLElement {
    const node = document.createElement('div');
    node.className = `el ${el.type}`;
    node.dataset.id = String(el.id);

    const bar = document.createElement('div');
    bar.className = 'bar';
    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = {TEXT: 'Tekstas', SHAPE: 'Figūra', CODE: 'Java'}[el.type];
    bar.append(title);
    if (el.type === 'CODE') {
        bar.append(button('▶ Run', 'run', () => run(el.id)));
    }
    bar.append(button('×', 'delete', () => remove(el.id), 'Pašalinti'));
    enableDrag(bar, el.id);

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
    return node;
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

// ---------- editing ----------

function enableDrag(handle: HTMLElement, id: number) {
    handle.addEventListener('pointerdown', down => {
        if ((down.target as HTMLElement).closest('button')) return;
        const el = elements.get(id)!;
        const startX = down.clientX - el.x, startY = down.clientY - el.y;
        handle.setPointerCapture(down.pointerId);
        const move = (e: PointerEvent) => {
            el.x = Math.max(0, Math.round(e.clientX - startX));
            el.y = Math.max(0, Math.round(e.clientY - startY));
            render(el);
        };
        const up = () => {
            handle.removeEventListener('pointermove', move);
            handle.removeEventListener('pointerup', up);
            save(id).catch(showError);
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
    const {type, x, y, width, height, content} = el;
    await api('PUT', `/api/boards/${current.id}/elements/${id}`, {type, x, y, width, height, content});
}

async function add(type: ElementType) {
    if (!current) return;
    const size = type === 'CODE' ? {width: 460, height: 300} : {width: 200, height: 100};
    const content = type === 'CODE'
        ? 'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello");\n    }\n}\n'
        : '';
    const offset = 20 * (elements.size % 10);
    render(await api<BoardElement>('POST', `/api/boards/${current.id}/elements`,
        {type, x: 60 + offset, y: 60 + offset, ...size, content}));
}

async function remove(id: number) {
    if (!current) return;
    await api('DELETE', `/api/boards/${current.id}/elements/${id}`).catch(showError);
    drop(id);
}

function drop(id: number) {
    nodes.get(id)?.remove();
    nodes.delete(id);
    elements.delete(id);
}

async function run(id: number) {
    const node = nodes.get(id);
    if (!current || !node) return;
    const runButton = node.querySelector<HTMLButtonElement>('.run')!;
    runButton.disabled = true;
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
        b.addEventListener('click', () => add(b.dataset.add as ElementType).catch(showError)));
    await loadBoards();
}

start().catch(showError);
