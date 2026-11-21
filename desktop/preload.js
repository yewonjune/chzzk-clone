// 화면(renderer)에 노출하는 최소 기능: 서버 주소 설정 읽기·바꾸기, 외부 브라우저로 열기
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('chzzkDesktop', {
  getConfig: () => ipcRenderer.invoke('config:get'),
  setServerUrl: (url) => ipcRenderer.invoke('config:set', url),
  openExternal: (url) => ipcRenderer.invoke('open:external', url),
});
