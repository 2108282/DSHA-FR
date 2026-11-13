/**
 * Document-level paste routing across every mounted composer (rc.2 parity).
 *
 * A file paste outside an editable control addresses at most one composer: the
 * one that most recently held focus while it can accept files, otherwise the
 * main Conversation's composer while it can. A composer inside a hidden or
 * inert subtree, or behind the foreground modal dialog or menu, is never
 * addressed.
 *
 * This is the rc.2 `input/file-intake.ts` route, restored here because the
 * vendored 0.2.1 engine predates it. It only reads the shared `modalSelector`
 * constant, which both rc.1 and rc.2 publish.
 */
/** Controls that own their own paste; the document-level route never takes it from them. */
const FILE_INTAKE_EDITABLE = "input, textarea, select, [contenteditable]:not([contenteditable=\"false\"])";

/**
* Clipboard files in clipboard order, with members the entry API reports as directories.
* @param transfer - clipboard payload of one paste event.
* @returns the pasted files and their directory members.
*/
function clipboardFiles(transfer) {
	const files = [];
	const directories = /* @__PURE__ */ new Set();
	for (const item of transfer.items) {
		if (item.kind !== "file") continue;
		const file = item.getAsFile();
		if (file === null) continue;
		files.push(file);
		if (typeof item.webkitGetAsEntry === "function" && item.webkitGetAsEntry()?.isDirectory === true) directories.add(file);
	}
	return {
		files,
		directories
	};
}

/**
* Create the paste router shared by the composers of one plugin instance.
* Its document listeners exist only while at least one composer is registered.
* @returns the registration callback injected into each composer.
*/
function createComposerFileIntake() {
	const targets = /* @__PURE__ */ new Set();
	let focused;
	let uninstall;
	const addressable = (doc) => {
		const foreground = [...doc.querySelectorAll(_deepseek_ai_dsh_client_ui_primitives.modalSelector)].at(-1);
		return [...targets].filter((target) => target.card.isConnected && target.card.closest("[inert], [hidden]") === null && (foreground === void 0 || foreground.contains(target.card)));
	};
	const pasteTarget = (doc) => {
		const candidates = addressable(doc).filter((target) => target.accepts());
		if (focused !== void 0 && candidates.includes(focused)) return focused;
		return candidates.find((target) => target.occurrence === "main");
	};
	const install = (doc) => {
		const focusin = (event) => {
			const node = event.target;
			const target = [...targets].find((candidate) => candidate.card.contains(node));
			if (target !== void 0) focused = target;
		};
		const paste = (event) => {
			if (event.defaultPrevented || event.clipboardData === null) return;
			const origin = event.composedPath().find((value) => value instanceof Element);
			if (origin instanceof Element && origin.closest(FILE_INTAKE_EDITABLE) !== null) return;
			const { files, directories } = clipboardFiles(event.clipboardData);
			if (files.length === 0) return;
			const target = pasteTarget(doc);
			if (target === void 0) return;
			event.preventDefault();
			target.add(files, directories.size === 0 ? void 0 : directories);
		};
		doc.addEventListener("focusin", focusin, true);
		doc.addEventListener("paste", paste);
		return () => {
			doc.removeEventListener("focusin", focusin, true);
			doc.removeEventListener("paste", paste);
		};
	};
	return (target) => {
		targets.add(target);
		const doc = target.card.ownerDocument;
		if (target.card.contains(doc.activeElement)) focused = target;
		uninstall ??= install(doc);
		return () => {
			targets.delete(target);
			if (focused === target) focused = void 0;
			if (targets.size > 0) return;
			uninstall?.();
			uninstall = void 0;
		};
	};
}
