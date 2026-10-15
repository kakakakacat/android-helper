import { cloudflare } from "@cloudflare/vite-plugin";
import { defineConfig } from "vite";

// cf dev / cf build / cf deploy 都走这里；本地开发不连远程资源。
export default defineConfig({
	plugins: [cloudflare({ remoteBindings: false })],
});
