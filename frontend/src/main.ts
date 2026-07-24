import { createApp } from 'vue'
import App from './App.vue'
import KwsPage from './KwsPage.vue'
import './style.css'

// 根据 URL 分发页面:
//   /                -> App.vue (原有 v2 语音对话页面)
//   /kws             -> KwsPage.vue (v3 KWS 唤醒页面)
//   /#/kws 或 ?kws=1 -> KwsPage.vue (跨 frp/CDN 更可靠,不依赖服务器 SPA fallback)
//
// 双路由原因:frp/reverse proxy 不一定都支持 SPA fallback (访问 /kws 返回 index.html),
// hash 和 query 从不到服务器,100% 可用。手动拼接 URL 也友好。
const isKws =
    location.pathname === '/kws' ||
    location.hash === '#/kws' ||
    new URLSearchParams(location.search).get('kws') === '1'

const Page = isKws ? KwsPage : App
createApp(Page).mount('#app')
