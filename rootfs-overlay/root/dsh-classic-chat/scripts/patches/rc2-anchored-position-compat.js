/**
 * rc.2 removed `useAnchoredPosition` from `@deepseek-ai/dsh-client-ui-primitives`
 * (the official composer replaced it with CSS anchor positioning). The vendored
 * 0.2.1 conversation and chat modules still call it, and a missing export throws
 * inside the `conversation.composer.bar` slot entry, which removes the whole
 * composer. Prefer the host export when a future release restores it; otherwise
 * use this local copy of the rc.1 implementation.
 *
 * This file is a snippet consumed by scripts/patch-rc2-compat.mjs; it is spliced
 * into lib/client.js at file scope, where `react` and the primitives namespace
 * are passed in by each module factory.
 */
function _dshCreateAnchoredPositionHook(react, primitives) {
	if (typeof primitives.useAnchoredPosition === "function") return primitives.useAnchoredPosition;
	const useState = react.useState;
	const useLayoutEffect = react.useLayoutEffect;
	/**
	* Frame clearance an overlay keeps above its anchoring control.
	* @param min - minimum margin.
	* @param anchor - anchor rect in viewport coordinates, or undefined.
	* @param gap - the distance the overlay keeps below `anchor`.
	* @returns the frame clearance plus 20px, capped by a visible `anchor`, never below `min`.
	*/
	function overlayTopMargin(min, anchor, gap = 0) {
		const root = document.documentElement;
		const clearance = Number.parseFloat(getComputedStyle(root).getPropertyValue("--dsh-frame-top-clearance"));
		if (Number.isNaN(clearance)) return min;
		const cap = anchor !== void 0 && anchor.top >= 0 ? anchor.bottom + gap : Number.POSITIVE_INFINITY;
		return Math.max(min, Math.min((root.hasAttribute("data-fullscreen") ? 0 : clearance) + 20, cap));
	}
	return function useAnchoredPosition(options) {
		const { open, anchorRef, panelRef, side = "bottom", align = "start", gap, margin, getAnchorRect, layoutKey, minWidthFromAnchor = false, frameClearance = true } = options;
		const [position, setPosition] = useState(null);
		useLayoutEffect(() => {
			if (!open) {
				setPosition(null);
				return;
			}
			const anchor = anchorRef.current;
			const panel = panelRef.current;
			if (anchor === null || panel === null) return;
			const win = anchor.ownerDocument.defaultView;
			if (win === null) return;
			const viewport = win.visualViewport;
			const visualOrigin = typeof win.CSS !== "undefined" && win.CSS.supports("-webkit-backdrop-filter", "none");
			const previous = [
				"--dsh-anchored-max-width",
				"--dsh-anchored-max-height",
				...minWidthFromAnchor ? ["min-width"] : []
			].map((name) => [
				name,
				panel.style.getPropertyValue(name),
				panel.style.getPropertyPriority(name)
			]);
			let active = true;
			let lastRect = null;
			const readRect = () => getAnchorRect === void 0 ? anchor.getBoundingClientRect() : getAnchorRect();
			const place = (rect) => {
				lastRect = rect === null ? null : {
					x: rect.x,
					y: rect.y,
					width: rect.width,
					height: rect.height
				};
				let next = null;
				if (rect !== null) {
					const x = viewport?.offsetLeft ?? 0;
					const y = viewport?.offsetTop ?? 0;
					const width = viewport?.width ?? (anchor.ownerDocument.documentElement.clientWidth || win.innerWidth);
					const height = viewport?.height ?? (anchor.ownerDocument.documentElement.clientHeight || win.innerHeight);
					const topMargin = frameClearance ? overlayTopMargin(margin, rect, gap) : margin;
					const maxWidth = Math.max(0, width - margin * 2);
					panel.style.setProperty("--dsh-anchored-max-width", `${maxWidth}px`);
					panel.style.setProperty("--dsh-anchored-max-height", `${Math.max(0, height - topMargin - margin)}px`);
					if (minWidthFromAnchor) panel.style.minWidth = `${Math.min(rect.width, maxWidth)}px`;
					const panelWidth = panel.offsetWidth;
					const panelHeight = panel.offsetHeight;
					const offsetX = visualOrigin ? x : 0;
					const offsetY = visualOrigin ? y : 0;
					const left = offsetX + (side === "right" ? rect.right + gap : align === "end" ? rect.right - panelWidth : rect.left);
					const top = offsetY + (side === "right" ? rect.top : side === "top" ? rect.top - gap - panelHeight : rect.bottom + gap);
					next = {
						left: Math.max(x + margin, Math.min(left, x + width - margin - panelWidth)),
						top: Math.max(y + topMargin, Math.min(top, y + height - margin - panelHeight))
					};
				}
				setPosition((current) => current?.left === next?.left && current?.top === next?.top ? current : next);
			};
			const update = () => {
				if (!active) return;
				place(readRect());
			};
			update();
			win.addEventListener("scroll", update, true);
			win.addEventListener("resize", update);
			viewport?.addEventListener("scroll", update);
			viewport?.addEventListener("resize", update);
			const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(update);
			observer?.observe(anchor);
			observer?.observe(panel);
			let frame = 0;
			const track = () => {
				if (!active) return;
				const rect = readRect();
				if (rect?.x !== lastRect?.x || rect?.y !== lastRect?.y || rect?.width !== lastRect?.width || rect?.height !== lastRect?.height) place(rect);
				frame = win.requestAnimationFrame(track);
			};
			frame = win.requestAnimationFrame(track);
			return () => {
				active = false;
				win.cancelAnimationFrame(frame);
				observer?.disconnect();
				win.removeEventListener("scroll", update, true);
				win.removeEventListener("resize", update);
				viewport?.removeEventListener("scroll", update);
				viewport?.removeEventListener("resize", update);
				for (const [name, value, priority] of previous) panel.style.setProperty(name, value, priority);
			};
		}, [
			open,
			anchorRef,
			panelRef,
			side,
			align,
			gap,
			margin,
			getAnchorRect,
			layoutKey,
			minWidthFromAnchor,
			frameClearance
		]);
		return position;
	};
}
