<script setup lang="ts">
/**
 * 图标精灵。
 *
 * 直接搬运 docs/prototypes/shangan-v2-prototype.html 的 SVG symbol 定义，
 * 保证后台图标与原型逐笔一致；新增图标必须先加到原型里。
 */
</script>

<template>
  <svg width="0" height="0" style="position: absolute" aria-hidden="true">
    <symbol id="i-home" viewBox="0 0 24 24">
      <path d="M3 10.5 12 3l9 7.5" /><path d="M5.5 9.5V20h13V9.5" />
    </symbol>
    <symbol id="i-book" viewBox="0 0 24 24">
      <path d="M4 4.5h6a2 2 0 0 1 2 2V20a1.6 1.6 0 0 0-1.6-1.6H4z" />
      <path d="M20 4.5h-6a2 2 0 0 0-2 2V20a1.6 1.6 0 0 1 1.6-1.6H20z" />
    </symbol>
    <symbol id="i-chart" viewBox="0 0 24 24">
      <path d="M4 20V10" /><path d="M10 20V4" /><path d="M16 20v-7" /><path d="M22 20H2" />
    </symbol>
    <symbol id="i-user" viewBox="0 0 24 24">
      <circle cx="12" cy="8" r="3.6" />
      <path d="M4.5 20c1.2-4 4-5.6 7.5-5.6S18.3 16 19.5 20" />
    </symbol>
    <symbol id="i-clock" viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="8.6" /><path d="M12 7.4V12l3.4 2.1" />
    </symbol>
    <symbol id="i-check" viewBox="0 0 24 24"><path d="M4.5 12.8 9.4 17.6 19.5 6.8" /></symbol>
    <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 4.5v15" /><path d="M4.5 12h15" /></symbol>
    <symbol id="i-search" viewBox="0 0 24 24">
      <circle cx="11" cy="11" r="6.6" /><path d="M15.9 15.9 20.5 20.5" />
    </symbol>
    <symbol id="i-bell" viewBox="0 0 24 24">
      <path d="M6.4 10.2a5.6 5.6 0 0 1 11.2 0c0 4.4 1.6 5.8 1.6 5.8H4.8s1.6-1.4 1.6-5.8z" />
      <path d="M10.2 19a2 2 0 0 0 3.6 0" />
    </symbol>
    <symbol id="i-chev" viewBox="0 0 24 24"><path d="M9.5 5.5 16 12l-6.5 6.5" /></symbol>
    <symbol id="i-alert" viewBox="0 0 24 24">
      <path d="M12 4.5 21 19.5H3z" /><path d="M12 10v4.2" />
      <circle cx="12" cy="17" r=".9" fill="currentColor" stroke="none" />
    </symbol>
    <symbol id="i-edit" viewBox="0 0 24 24">
      <path d="M16.4 4.2l3.4 3.4L8.6 18.8l-4.1.7.7-4.1z" />
    </symbol>
    <symbol id="i-trash" viewBox="0 0 24 24">
      <path d="M4.6 7.2h14.8" /><path d="M6.6 7.2 7.6 20h8.8l1-12.8" />
      <path d="M9.6 7.2V4.6h4.8v2.6" />
    </symbol>
    <symbol id="i-cal" viewBox="0 0 24 24">
      <rect x="3.8" y="5.4" width="16.4" height="14.4" rx="2.4" />
      <path d="M3.8 10h16.4" /><path d="M8 3.6v3" /><path d="M16 3.6v3" />
    </symbol>
    <symbol id="i-server" viewBox="0 0 24 24">
      <rect x="3.6" y="4.4" width="16.8" height="6" rx="1.8" />
      <rect x="3.6" y="13.6" width="16.8" height="6" rx="1.8" />
      <path d="M7 7.4h.01" /><path d="M7 16.6h.01" />
    </symbol>
    <symbol id="i-pulse" viewBox="0 0 24 24">
      <path d="M2.5 12.5h4L9 7l3.4 10L15 12.5h6.5" />
    </symbol>
    <symbol id="i-close" viewBox="0 0 24 24"><path d="M6 6l12 12" /><path d="M18 6 6 18" /></symbol>
    <symbol id="i-star" viewBox="0 0 24 24">
      <path d="M12 4l2.5 5.2 5.7.8-4.1 4 1 5.6L12 17l-5.1 2.6 1-5.6-4.1-4 5.7-.8z" />
    </symbol>
    <symbol id="i-lock" viewBox="0 0 24 24">
      <rect x="4.8" y="10.5" width="14.4" height="9.4" rx="2.2" />
      <path d="M8.4 10.5V8a3.6 3.6 0 0 1 7.2 0v2.5" />
    </symbol>
    <symbol id="i-archive" viewBox="0 0 24 24">
      <rect x="3.6" y="5" width="16.8" height="4.4" rx="1.4" />
      <path d="M5.4 9.4V19h13.2V9.4" /><path d="M9.8 13h4.4" />
    </symbol>
    <symbol id="i-timer" viewBox="0 0 24 24">
      <path d="M9.5 3h5" /><circle cx="12" cy="13.5" r="7.2" /><path d="M12 9.8v3.7h2.8" />
    </symbol>
    <symbol id="i-log" viewBox="0 0 24 24">
      <path d="M6 4.5h9.5L18.5 8v12H6z" />
      <path d="M15 4.5V8h3.5" />
      <path d="M8.5 12h7" />
      <path d="M8.5 15.5h7" />
    </symbol>
  </svg>
</template>
