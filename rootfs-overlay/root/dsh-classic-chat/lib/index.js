import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, dirname, isAbsolute, join } from "node:path";
import Schema from "@deepseek-ai/schemastery";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
//#region src/settings-channel.ts
/** Largest request body this channel buffers; settings payloads are tiny. */
const MAX_REQUEST_BYTES = 1 << 20;
/** Endpoint segment shape accepted by the Connection router, mirrored here. */
const ENDPOINT_SEGMENT = /^[A-Za-z0-9_$.-]+$/;
/** Envelope discriminator the browser caller sends. */
const CLIENT_REQUEST = "client-request";
/** Envelope discriminator the browser caller expects back. */
const SERVER_RESPONSE = "server-response";
/**
* Decode one request envelope, dispatch it, and write the decoded reply.
* Mirrors the Connection router's own carrier rules: method and endpoint must
* agree, the body must be JSON, and only endpoint failures are results while
* transport failures are statuses.
*/
async function answer(req, res, channel, handler) {
	const endpoint = endpointOf(channel, req.url);
	if (req.method !== "POST" || endpoint === void 0) {
		res.writeHead(404);
		res.end("not found");
		return;
	}
	if ((req.headers["content-type"] ?? "").split(";", 1)[0]?.trim().toLowerCase() !== "application/json") {
		res.writeHead(415);
		res.end("content type must be application/json");
		return;
	}
	const body = await readBody(req);
	if (body.kind === "too-large") {
		res.writeHead(413);
		res.end("request body too large");
		return;
	}
	if (body.kind === "invalid") {
		res.writeHead(400);
		res.end("body is not JSON");
		return;
	}
	const envelope = requestEnvelope(body.value);
	if (envelope === void 0) {
		write(res, rpcIdOf(body.value), {
			ok: false,
			error: {
				code: "gateway/bad-request",
				message: "invalid client-request message",
				details: { issues: [] }
			}
		});
		return;
	}
	if (envelope.method !== endpoint) {
		write(res, envelope.rpcId, {
			ok: false,
			error: {
				code: "gateway/bad-request",
				message: `method ${JSON.stringify(envelope.method)} does not match endpoint ${JSON.stringify(endpoint)}`,
				details: { issues: [] }
			}
		});
		return;
	}
	const controller = new AbortController();
	res.on("close", () => {
		controller.abort();
	});
	try {
		write(res, envelope.rpcId, await handler(endpoint, envelope.payload, controller.signal));
	} catch (error) {
		res.writeHead(500);
		res.end(`handler failure: ${String(error)}`);
	}
}
/**
* Resolve the channel-relative endpoint for one request path.
* @returns The endpoint, or `undefined` when the path is outside the channel
* or carries a segment the Connection router would refuse.
*/
function endpointOf(channel, rawUrl) {
	const pathname = new URL(rawUrl ?? "/", "http://dsh.internal").pathname;
	if (!pathname.startsWith(`${channel}/`)) return void 0;
	const endpoint = pathname.slice(channel.length + 1);
	if (endpoint.split("/").some((segment) => segment === "" || segment === "." || segment === ".." || !ENDPOINT_SEGMENT.test(segment))) return;
	return endpoint;
}
/** Structure one decoded request, rejecting anything the caller could not have sent. */
function requestEnvelope(value) {
	if (typeof value !== "object" || value === null || Array.isArray(value)) return void 0;
	const record = value;
	if (record.type !== CLIENT_REQUEST) return void 0;
	if (typeof record.rpcId !== "string" || typeof record.method !== "string") return void 0;
	return {
		rpcId: record.rpcId,
		method: record.method,
		payload: record.payload
	};
}
/** Recover the correlation id of a malformed envelope so the caller can match it. */
function rpcIdOf(value) {
	const raw = value?.rpcId;
	return typeof raw === "string" ? raw : "invalid-request";
}
/** Write one decoded reply in the envelope the browser caller parses. */
function write(res, rpcId, result) {
	res.writeHead(200, { "content-type": "application/json" });
	res.end(JSON.stringify({
		type: SERVER_RESPONSE,
		rpcId,
		result
	}));
}
/** Buffer one request body, refusing both malformed JSON and unbounded input. */
async function readBody(req) {
	const chunks = [];
	let bytes = 0;
	for await (const chunk of req) {
		const buffer = chunk;
		bytes += buffer.length;
		if (bytes > MAX_REQUEST_BYTES) return { kind: "too-large" };
		chunks.push(buffer);
	}
	try {
		return {
			kind: "value",
			value: JSON.parse(Buffer.concat(chunks).toString("utf8"))
		};
	} catch {
		return { kind: "invalid" };
	}
}
//#endregion
//#region src/config.ts
/** Defaults shared by the Host schema and the client-side fallback. */
const DEFAULT_STREAM_CONFIG = {
	mode: "typewriter",
	preset: "balanced",
	revealCharsPerSec: 80,
	scrollSpeedPxPerSec: 48,
	maxScrollSpeedPxPerSec: 1e3
};
/**
* Window global the Host writes into the served index HTML. The browser boot
* graph carries no per-entry config, so this inline script is the only
* Host-to-client configuration channel for a composed web plugin.
*/
const STREAM_BOOT_GLOBAL = "__DSH_SMOOTH_STREAM_CONFIG__";
//#endregion
//#region src/boot-config.ts
/**
* Host-rendered configuration bootstrap for the browser half: each index
* response embeds the schema-validated plugin config as a window global the
* client entry reads at apply time. Same pattern as ui-theme's boot theme.
*/
/** Build the inline script assigning the validated config to the boot global. */
function bootConfigScript(config) {
	return `<script>window[${JSON.stringify(STREAM_BOOT_GLOBAL)}]=${JSON.stringify(config)}<\/script>`;
}
/**
* Insert the config bootstrap immediately after the opening body tag, before
* any plugin bundle runs. Body-less fragments receive it at the end, where
* the HTML parser has already synthesized a body.
* @param html - Raw application index HTML.
* @param config - Schema-validated plugin configuration.
* @returns HTML containing the config bootstrap.
*/
function injectStreamConfig(html, config) {
	const script = bootConfigScript(config);
	const body = /<body(?:\s[^>]*)?>/i.exec(html);
	if (body === null) return `${html}${script}`;
	const at = body.index + body[0].length;
	return `${html.slice(0, at)}${script}${html.slice(at)}`;
}
//#endregion
//#region src/package-meta.ts
/** Host-only package metadata. The package manifest remains the source of truth. */
function packageManifestPath() {
	try {
		return join(dirname(fileURLToPath(import.meta.url)), "..", "package.json");
	} catch {}
	return join(process.cwd(), "package.json");
}
const manifest = JSON.parse(readFileSync(packageManifestPath(), "utf8"));
function readRequiredString(field) {
	const value = manifest[field];
	if (typeof value !== "string" || value.length === 0) throw new Error(`dsh-smooth-stream: package.json must contain a non-empty ${field}`);
	return value;
}
/** npm package name, read from this plugin's manifest. */
const STREAM_PACKAGE_NAME = readRequiredString("name");
/** Version of the code currently loaded by the Host, read from its manifest. */
const STREAM_PACKAGE_VERSION = readRequiredString("version");
//#endregion
//#region src/profile-installation.ts
/** Host-only profile source inspection and fixed npm update runner. */
function record(value) {
	return typeof value === "object" && value !== null && !Array.isArray(value) ? value : void 0;
}
function profileDirectory(baseUrl) {
	if (baseUrl === void 0) return void 0;
	try {
		const url = new URL(baseUrl);
		return url.protocol === "file:" ? fileURLToPath(url) : void 0;
	} catch {
		return;
	}
}
function hasBundle(manifest, packageName) {
	const bundles = record(record(manifest.dsh)?.profile)?.bundles;
	return Array.isArray(bundles) && bundles.includes(packageName);
}
function isLocalSpecifier(specifier) {
	return specifier.startsWith("link:") || specifier.startsWith("file:") || specifier.startsWith(".") || isAbsolute(specifier) || /^[A-Za-z]:[\\/]/.test(specifier);
}
function isRegistrySpecifier(specifier) {
	return specifier.length > 0 && !specifier.includes(":") && !/[\\/]/.test(specifier);
}
function isNpmSpecifier(specifier, packageName) {
	if (!specifier.startsWith("npm:")) return isRegistrySpecifier(specifier);
	const aliased = specifier.slice(4);
	if (aliased === packageName) return true;
	const prefix = `${packageName}@`;
	return aliased.startsWith(prefix) && isRegistrySpecifier(aliased.slice(prefix.length));
}
/**
* Read only the profile manifest anchored by the current Cordis config tree.
* A malformed or unrelated tree never receives an update affordance.
*/
function inspectProfileInstallation(baseUrl, packageName) {
	const profileDir = profileDirectory(baseUrl);
	if (profileDir === void 0) return { kind: "unmanaged" };
	let manifest;
	try {
		manifest = JSON.parse(readFileSync(join(profileDir, "package.json"), "utf8"));
	} catch {
		return { kind: "unmanaged" };
	}
	const specifier = record(manifest.dependencies)?.[packageName];
	if (typeof specifier !== "string" || !hasBundle(manifest, packageName)) return { kind: "unmanaged" };
	if (isLocalSpecifier(specifier)) return { kind: "development" };
	if (!isNpmSpecifier(specifier, packageName)) return { kind: "unmanaged" };
	return {
		kind: "npm",
		profileDir,
		profileName: basename(profileDir)
	};
}
/** Run the same fixed package update operation that a profile user would invoke. */
function updateNpmProfilePackage(profileDir, packageName) {
	const command = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
	return new Promise((resolve, reject) => {
		const child = spawn(command, ["update", packageName], {
			cwd: profileDir,
			stdio: "ignore",
			shell: false,
			windowsHide: true
		});
		child.once("error", reject);
		child.once("exit", (code, signal) => {
			if (code === 0) resolve();
			else reject(/* @__PURE__ */ new Error(`dsh-smooth-stream: pnpm update failed (${signal ?? String(code)})`));
		});
	});
}
//#endregion
//#region src/settings-api.ts
/** Shared wire vocabulary for the plugin-owned settings RPC channel. */
/** Dedicated, loopback-only RPC channel registered by the Host half. */
const STREAM_SETTINGS_RPC_CHANNEL = "/smooth-stream";
/** Endpoints accepted by {@link STREAM_SETTINGS_RPC_CHANNEL}. */
const STREAM_SETTINGS_RPC = {
	read: "settings.read",
	write: "settings.write",
	debugRead: "debug.read",
	debugWrite: "debug.write",
	upgrade: "plugin.upgrade"
};
//#endregion
//#region src/settings.ts
/**
* User-owned settings for the smooth-stream plugin, exposed to the Host
* settings service and edited from the Web Settings "plugin configuration"
* page. This is the runtime-editable complement to {@link StreamConfig}: that
* contract is composed at load and bridged once through the boot global, while
* these preferences live in the durable user-settings document and take effect
* live.
*/
/** Settings namespace registered by the Host and served through the plugin RPC. */
const STREAM_SETTINGS_NS = "smooth-stream";
/** Defaults shared by the Host schema and the client-side fallback. */
const DEFAULT_STREAM_SETTINGS = {
	enabled: true,
	controlScroll: true,
	motionPreference: "force-smooth",
	thinkAutoExpand: true,
	logarithmicFade: true,
	fastFold: true,
	fastPipeline: true,
	keepStreamOnToolCall: true,
	debugEnabled: false,
	debugTuning: {
		revealScale: 1,
		queuePressure: .85,
		maxRevealCps: 600,
		cadenceClamp: 4,
		springStiffness: 130,
		springDamping: 24,
		springMass: 1,
		runwayPx: 72,
		reserveResponseMs: 180,
		backpressureMinScale: .55
	}
};
//#endregion
//#region src/plugin.ts
/** Display name shown by the Host loader while the plugin is mounted. */
const name = "dsh-smooth-stream";
const Config = Schema.object({
	mode: Schema.union(["typewriter", "teleprompter"]).default(DEFAULT_STREAM_CONFIG.mode),
	preset: Schema.union([
		"realtime",
		"balanced",
		"silky"
	]).default(DEFAULT_STREAM_CONFIG.preset),
	revealCharsPerSec: Schema.number().min(5).max(200).default(DEFAULT_STREAM_CONFIG.revealCharsPerSec),
	scrollSpeedPxPerSec: Schema.number().min(1).max(200).default(DEFAULT_STREAM_CONFIG.scrollSpeedPxPerSec),
	maxScrollSpeedPxPerSec: Schema.number().min(1).max(2e3).default(DEFAULT_STREAM_CONFIG.maxScrollSpeedPxPerSec)
});
Schema.object({
	enabled: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.enabled),
	controlScroll: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.controlScroll),
	motionPreference: Schema.union([
		Schema.const("auto"),
		Schema.const("force-smooth"),
		Schema.const("force-reduced")
	]).default(DEFAULT_STREAM_SETTINGS.motionPreference),
	thinkAutoExpand: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.thinkAutoExpand),
	logarithmicFade: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.logarithmicFade),
	fastFold: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.fastFold),
	fastPipeline: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.fastPipeline),
	keepStreamOnToolCall: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.keepStreamOnToolCall),
	debugEnabled: Schema.boolean().default(DEFAULT_STREAM_SETTINGS.debugEnabled),
	debugTuning: Schema.object({
		revealScale: Schema.number().min(.25).max(2).default(DEFAULT_STREAM_SETTINGS.debugTuning.revealScale),
		queuePressure: Schema.number().min(0).max(2).default(DEFAULT_STREAM_SETTINGS.debugTuning.queuePressure),
		maxRevealCps: Schema.number().min(120).max(1e3).default(DEFAULT_STREAM_SETTINGS.debugTuning.maxRevealCps),
		cadenceClamp: Schema.number().min(1).max(24).default(DEFAULT_STREAM_SETTINGS.debugTuning.cadenceClamp ?? 4),
		springStiffness: Schema.number().min(40).max(320).default(DEFAULT_STREAM_SETTINGS.debugTuning.springStiffness),
		springDamping: Schema.number().min(8).max(80).default(DEFAULT_STREAM_SETTINGS.debugTuning.springDamping),
		springMass: Schema.number().min(.5).max(3).default(DEFAULT_STREAM_SETTINGS.debugTuning.springMass),
		runwayPx: Schema.number().min(0).max(120).default(DEFAULT_STREAM_SETTINGS.debugTuning.runwayPx),
		reserveResponseMs: Schema.number().min(60).max(600).default(DEFAULT_STREAM_SETTINGS.debugTuning.reserveResponseMs),
		backpressureMinScale: Schema.number().min(.25).max(1).default(DEFAULT_STREAM_SETTINGS.debugTuning.backpressureMinScale)
	})
});
/**
* Host half: log the resolved configuration and bridge it to the browser
* half. The web boot graph carries no per-entry config, so the validated
* value is injected into every served index response as a boot global the
* client entry reads at apply time.
* @param ctx - Host context carrying the web server service when composed.
* @param config - Schema-validated configuration with defaults filled.
*/
function apply(ctx, config) {
	ctx.logger("classic-chat").info("DSH Classic Chat & Smooth Stream Host Services active.");
	ctx.provide("uiConversation", {
		name: "classic-ui-conversation",
		active: true
	});
	ctx.provide("conversation", {
		name: "classic-conversation",
		active: true
	});
	ctx.inject(["settings"], (child) => {
		child.effect(() => child.settings.configure({ auto: false }, ctx.fiber));
	});
	console.log(`[dsh-smooth-stream] plugin loaded! mode=${config.mode} preset=${config.preset} seed=${config.revealCharsPerSec}cps scroll=${config.scrollSpeedPxPerSec}px/s maxScroll=${config.maxScrollSpeedPxPerSec}px/s`);
	const SETTINGS_FILE = join(process.env.DSH_HOME || "/root/.dsh", "storages", "smooth-stream-settings.json");
	function loadSettings() {
		try {
			if (existsSync(SETTINGS_FILE)) {
				const saved = JSON.parse(readFileSync(SETTINGS_FILE, "utf8"));
				return {
					...DEFAULT_STREAM_SETTINGS,
					...saved,
					debugTuning: {
						...DEFAULT_STREAM_SETTINGS.debugTuning,
						...saved.debugTuning || {}
					}
				};
			}
		} catch (e) {
			console.warn("[dsh-smooth-stream] loadSettings error:", e);
		}
		return {
			...DEFAULT_STREAM_SETTINGS,
			debugTuning: { ...DEFAULT_STREAM_SETTINGS.debugTuning }
		};
	}
	function saveSettings(settings) {
		try {
			mkdirSync(dirname(SETTINGS_FILE), { recursive: true });
			writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2), "utf8");
		} catch (e) {
			console.warn("[dsh-smooth-stream] saveSettings error:", e);
		}
	}
	let currentSettings = loadSettings();
	ctx.inject(["webServer"], (httpCtx) => {
		httpCtx.effect(() => httpCtx.webServer.tapIndex((html) => injectStreamConfig(html, config)), "dsh-smooth-stream: boot config bridge");
		let connectionSvc = null;
		ctx.inject(["connection"], (c) => {
			connectionSvc = c.connection;
		});
		const view = () => {
			const baseUrl = connectionSvc?.baseUrl;
			const installation = inspectProfileInstallation(baseUrl, STREAM_PACKAGE_NAME);
			return {
				version: STREAM_PACKAGE_VERSION,
				installation: installation.kind === "unmanaged" ? "development" : installation.kind,
				writable: true,
				enabled: currentSettings.enabled,
				controlScroll: currentSettings.controlScroll,
				motionPreference: currentSettings.motionPreference,
				thinkAutoExpand: currentSettings.thinkAutoExpand,
				logarithmicFade: currentSettings.logarithmicFade,
				fastFold: currentSettings.fastFold ?? DEFAULT_STREAM_SETTINGS.fastFold,
				fastPipeline: currentSettings.fastPipeline ?? DEFAULT_STREAM_SETTINGS.fastPipeline,
				keepStreamOnToolCall: currentSettings.keepStreamOnToolCall,
				canUpgrade: installation.kind === "npm"
			};
		};
		const debugView = () => {
			return {
				debugEnabled: currentSettings.debugEnabled,
				tuning: { ...currentSettings.debugTuning }
			};
		};
		const validDebugTuning = (value) => {
			if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
			const tuning = value;
			return typeof tuning.revealScale === "number" && tuning.revealScale >= .25 && tuning.revealScale <= 2 && typeof tuning.queuePressure === "number" && tuning.queuePressure >= 0 && tuning.queuePressure <= 2 && typeof tuning.maxRevealCps === "number" && tuning.maxRevealCps >= 120 && tuning.maxRevealCps <= 1e3 && typeof tuning.springStiffness === "number" && tuning.springStiffness >= 40 && tuning.springStiffness <= 320 && typeof tuning.springDamping === "number" && tuning.springDamping >= 8 && tuning.springDamping <= 80 && typeof tuning.springMass === "number" && tuning.springMass >= .5 && tuning.springMass <= 3 && typeof tuning.runwayPx === "number" && tuning.runwayPx >= 0 && tuning.runwayPx <= 120 && typeof tuning.reserveResponseMs === "number" && tuning.reserveResponseMs >= 60 && tuning.reserveResponseMs <= 600 && typeof tuning.backpressureMinScale === "number" && tuning.backpressureMinScale >= .25 && tuning.backpressureMinScale <= 1;
		};
		let upgrade;
		const handle = async (endpoint, payload) => {
			if (endpoint === STREAM_SETTINGS_RPC.read) return {
				ok: true,
				value: view()
			};
			if (endpoint === STREAM_SETTINGS_RPC.write) {
				if (typeof payload !== "object" || payload === null || Array.isArray(payload)) return {
					ok: false,
					error: {
						code: "settings-rejected",
						message: "payload must be an object",
						details: { ns: STREAM_SETTINGS_NS }
					}
				};
				const next = payload;
				if (typeof next.enabled === "boolean") currentSettings.enabled = next.enabled;
				if (typeof next.controlScroll === "boolean") currentSettings.controlScroll = next.controlScroll;
				if (next.motionPreference !== void 0) currentSettings.motionPreference = next.motionPreference;
				if (typeof next.thinkAutoExpand === "boolean") currentSettings.thinkAutoExpand = next.thinkAutoExpand;
				if (typeof next.logarithmicFade === "boolean") currentSettings.logarithmicFade = next.logarithmicFade;
				if (typeof next.fastFold === "boolean") currentSettings.fastFold = next.fastFold;
				if (typeof next.fastPipeline === "boolean") currentSettings.fastPipeline = next.fastPipeline;
				if (typeof next.keepStreamOnToolCall === "boolean") currentSettings.keepStreamOnToolCall = next.keepStreamOnToolCall;
				if (typeof next.debugEnabled === "boolean") currentSettings.debugEnabled = next.debugEnabled;
				if (next.debugTuning && validDebugTuning(next.debugTuning)) currentSettings.debugTuning = {
					...currentSettings.debugTuning,
					...next.debugTuning
				};
				saveSettings(currentSettings);
				return {
					ok: true,
					value: view()
				};
			}
			if (endpoint === STREAM_SETTINGS_RPC.debugRead) return {
				ok: true,
				value: debugView()
			};
			if (endpoint === STREAM_SETTINGS_RPC.debugWrite) {
				if (typeof payload !== "object" || payload === null || Array.isArray(payload)) return {
					ok: false,
					error: {
						code: "settings-rejected",
						message: "debug settings must be an object",
						details: { ns: STREAM_SETTINGS_NS }
					}
				};
				const next = payload;
				if (typeof next.debugEnabled === "boolean") currentSettings.debugEnabled = next.debugEnabled;
				if (next.tuning && validDebugTuning(next.tuning)) currentSettings.debugTuning = {
					...currentSettings.debugTuning,
					...next.tuning
				};
				saveSettings(currentSettings);
				return {
					ok: true,
					value: debugView()
				};
			}
			if (endpoint === STREAM_SETTINGS_RPC.upgrade) {
				const baseUrl = connectionSvc?.baseUrl;
				const installation = inspectProfileInstallation(baseUrl, STREAM_PACKAGE_NAME);
				if (installation.kind !== "npm") return {
					ok: false,
					error: {
						code: "internal",
						message: "smooth-stream is not an npm profile dependency",
						details: {}
					}
				};
				if (upgrade !== void 0) return {
					ok: false,
					error: {
						code: "internal",
						message: "smooth-stream update is already running",
						details: {}
					}
				};
				upgrade = updateNpmProfilePackage(installation.profileDir, STREAM_PACKAGE_NAME);
				try {
					await upgrade;
				} catch {
					return {
						ok: false,
						error: {
							code: "internal",
							message: "smooth-stream update failed",
							details: {}
						}
					};
				} finally {
					upgrade = void 0;
				}
				return {
					ok: true,
					value: { restartRequired: true }
				};
			}
			return {
				ok: false,
				error: {
					code: "internal",
					message: `unknown smooth-stream endpoint ${JSON.stringify(endpoint)}`,
					details: {}
				}
			};
		};
		httpCtx.effect(() => httpCtx.webServer.register({
			kind: "prefix",
			path: STREAM_SETTINGS_RPC_CHANNEL,
			handler: async (req, res) => {
				const conn = connectionSvc || ctx.get("connection");
				if (conn && typeof conn.requestRejection === "function") {
					const rejection = conn.requestRejection(req);
					if (rejection !== void 0) {
						res.writeHead(rejection);
						res.end(rejection === 401 ? "unauthorized" : "forbidden");
						return;
					}
				}
				await answer(req, res, STREAM_SETTINGS_RPC_CHANNEL, handle);
			}
		}), "dsh-smooth-stream: /smooth-stream direct route");
	});
}
//#endregion
export { Config, apply, name };
