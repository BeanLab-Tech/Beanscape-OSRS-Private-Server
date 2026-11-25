'use strict';

const { contextBridge, ipcRenderer } = require('electron');

const on = (channel, cb) => ipcRenderer.on(channel, (_e, payload) => cb(payload));
const call = (channel) => (...args) => ipcRenderer.invoke(channel, ...args);

contextBridge.exposeInMainWorld('beanscape', {
  status: call('status'),
  play: (id) => ipcRenderer.invoke('play', id),
  stop: call('stop'),
  installJava: call('install-java'),
  rebuild: call('rebuild'),
  adminStatus: call('admin-status'),
  adminInstall: call('admin-install'),
  adminRemove: call('admin-remove'),
  openRegister: call('open-register'),
  openData: call('open-data'),
  accounts: call('accounts-state'),
  saveAccount: call('accounts-save'),
  selectAccount: call('accounts-select'),
  deleteAccount: call('accounts-delete'),
  getHistory: call('history'),
  getFeed: call('feed'),
  getHealth: call('health'),
  getUpdates: call('updates'),
  getSettings: call('settings-get'),
  saveSettings: call('settings-save'),
  onLog: (cb) => on('log', cb),
  onProgress: (cb) => on('progress', cb),
  onState: (cb) => on('state', cb),
  onHealth: (cb) => on('health', cb),
});
