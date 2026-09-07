'use strict';

(() => {
  const status = document.getElementById('tile-status');
  const buttons = Object.fromEntries(['zoom-in', 'zoom-out', 'fit-route', 'recenter'].map(id => [id, document.getElementById(id)]));
  let map = null;
  let routePoints = [];
  let currentPosition = null;
  let positionMarker = null;
  let routeShapes = [];
  let routeKey = null;
  let activeRoute = null;
  let followPosition = false;
  let tileReceived = false;
  let tileFailed = false;
  let tileTimeout;

  function showStatus(message, error) {
    status.textContent = message;
    status.classList.toggle('error', !!error);
    status.hidden = !message;
  }
  function isTile(event) {
    return event.target instanceof HTMLImageElement && /\/tms\//.test(event.target.src);
  }
  document.getElementById('map').addEventListener('load', event => {
    if (!isTile(event)) return;
    tileReceived = true;
    if (!tileFailed) showStatus('', false);
    clearTimeout(tileTimeout);
  }, true);
  document.getElementById('map').addEventListener('error', event => {
    if (!isTile(event)) return;
    tileFailed = true;
    showStatus('TMAP 배경지도를 불러오지 못했습니다. 연결 상태를 확인해 주세요. 기존 안내는 계속됩니다.', true);
  }, true);
  window.addEventListener('offline', () => showStatus('인터넷 연결이 없습니다. TMAP 배경지도를 불러올 수 없습니다.', true));

  function validPoint(point) {
    return Array.isArray(point) && point.length === 2 && point.every(Number.isFinite) &&
      Math.abs(point[0]) <= 90 && Math.abs(point[1]) <= 180;
  }
  function coordinate(point) { return new Tmapv2.LatLng(point[0], point[1]); }
  function viewportSize() {
    const rect = document.getElementById('map').parentElement.getBoundingClientRect();
    return { width: Math.max(1, Math.round(rect.width)) + 'px', height: Math.max(1, Math.round(rect.height)) + 'px' };
  }
  function resizeMap() {
    if (!map) return;
    const size = viewportSize();
    const target = document.getElementById('map');
    target.style.width = size.width;
    target.style.height = size.height;
    map.resize();
  }
  function createMap(center) {
    if (!window.Tmapv2 || typeof Tmapv2.Map !== 'function') {
      showStatus('TMAP 지도를 시작할 수 없습니다. 지도 키와 인터넷 연결을 확인해 주세요.', true);
      return false;
    }
    showStatus('TMAP 배경지도를 불러오는 중입니다.', false);
    map = new Tmapv2.Map('map', {
      center: coordinate(center), ...viewportSize(), zoom: 17,
      httpsMode: true, zoomControl: false, scaleBar: true
    });
    map.addListener('dragstart', () => { followPosition = false; });
    map.addListener('zoom_changed', updateButtons);
    tileTimeout = setTimeout(() => {
      if (!tileReceived) showStatus('TMAP 배경지도 연결을 기다리고 있습니다. 기존 경로와 위치 표시는 계속됩니다.', true);
    }, 12000);
    return true;
  }
  function fitRoute() {
    if (!map || !routePoints.length) return;
    followPosition = false;
    resizeMap();
    const bounds = new Tmapv2.LatLngBounds();
    routePoints.forEach(point => bounds.extend(coordinate(point)));
    map.fitBounds(bounds, 45);
    if (map.getZoom() > 17) map.setZoom(17);
  }
  function marker(point, html, size, title) {
    return new Tmapv2.Marker({
      position: coordinate(point), iconHTML: html, iconSize: new Tmapv2.Size(size, size),
      offset: new Tmapv2.Point(size / 2, size / 2), title, map
    });
  }
  function updateButtons() {
    buttons['zoom-in'].disabled = !map || map.getZoom() >= map.getMaxZoomLevels();
    buttons['zoom-out'].disabled = !map || map.getZoom() <= map.getMinZoomLevels();
    buttons['fit-route'].disabled = !map || !routePoints.length;
    buttons.recenter.disabled = !map || !currentPosition;
  }
  buttons['zoom-in'].addEventListener('click', () => { if (map) map.zoomIn(); });
  buttons['zoom-out'].addEventListener('click', () => { if (map) map.zoomOut(); });
  buttons['fit-route'].addEventListener('click', fitRoute);
  buttons.recenter.addEventListener('click', () => {
    if (!map || !currentPosition) return;
    followPosition = true;
    map.setCenter(coordinate(currentPosition));
    if (map.getZoom() < 16) map.setZoom(16);
  });
  window.walksafeMapResize = resizeMap;
  window.walksafeMapUpdate = data => {
    document.getElementById('destination').textContent = data.destinationName || '선택한 목적지의 경로';
    document.getElementById('instruction').textContent = data.instruction || (data.active ? '현재 경로 안내 중' : '활성 경로 안내가 없습니다.');
    const nextPoints = (data.polyline || []).filter(validPoint);
    const guides = (data.guides || []).filter(validPoint);
    currentPosition = data.location && validPoint(data.location.point) ? data.location.point : null;
    document.getElementById('position-status').textContent = currentPosition
      ? '현재 위치 · 정확도 약 ' + Math.round(Math.max(0, Number(data.location.accuracyM) || 0)) + 'm'
      : '신뢰할 수 있는 현재 위치를 기다리고 있습니다.';
    if (!map) {
      const center = nextPoints[0] || currentPosition;
      if (!center || !createMap(center)) { updateButtons(); return; }
    }
    const nextKey = JSON.stringify([nextPoints, guides]);
    const geometryChanged = nextKey !== routeKey;
    if (geometryChanged || activeRoute !== data.active) {
      routeShapes.forEach(shape => shape.setMap(null));
      routeShapes = [];
      activeRoute = data.active;
      routePoints = nextPoints;
      routeKey = nextKey;
      if (routePoints.length) {
        routeShapes.push(new Tmapv2.Polyline({ path: routePoints.map(coordinate), strokeColor: '#ffffff', strokeWeight: 9, strokeOpacity: 1, map }));
        routeShapes.push(new Tmapv2.Polyline({ path: routePoints.map(coordinate), strokeColor: data.active ? '#146c83' : '#6b7980', strokeWeight: 5, strokeOpacity: 1, map }));
        guides.forEach((point, index) => routeShapes.push(marker(point, '<div class="guide-pin"></div>', 10, '안내 지점 ' + (index + 1))));
        routeShapes.push(marker(routePoints[0], '<div class="route-pin start-pin">출</div>', 34, '출발지'));
        routeShapes.push(marker(routePoints[routePoints.length - 1], '<div class="route-pin end-pin">도</div>', 34, '목적지'));
      }
    }
    if (currentPosition) {
      if (positionMarker) positionMarker.setPosition(coordinate(currentPosition));
      else positionMarker = marker(currentPosition, '<div class="current-pin"></div>', 20, '현재 위치');
    } else if (positionMarker) {
      positionMarker.setMap(null);
      positionMarker = null;
    }
    if (geometryChanged && routePoints.length) fitRoute();
    else if (currentPosition && followPosition) map.setCenter(coordinate(currentPosition));
    updateButtons();
  };
  updateButtons();
  window.addEventListener('resize', window.walksafeMapResize);
})();
