"use strict";

const demoDialog = document.querySelector(".demo-dialog");
const demoVideo = demoDialog.querySelector("video");
for (const trigger of document.querySelectorAll(".demo-trigger")) {
  trigger.addEventListener("click", (event) => {
    if (typeof demoDialog.showModal !== "function") return;
    event.preventDefault();
    demoDialog.showModal();
    demoVideo.play().catch(() => { /* Native controls remain available. */ });
  });
}
document.querySelector(".close-demo").addEventListener("click", () => demoDialog.close());
demoDialog.addEventListener("click", (event) => {
  const rect = demoDialog.getBoundingClientRect();
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) demoDialog.close();
});
demoDialog.addEventListener("close", () => demoVideo.pause());

const featureTabs = [...document.querySelectorAll(".feature-tab")];
function selectFeature(nextTab) {
  for (const tab of featureTabs) {
    const selected = tab === nextTab;
    tab.setAttribute("aria-selected", String(selected));
    tab.tabIndex = selected ? 0 : -1;
    document.getElementById(tab.getAttribute("aria-controls")).hidden = !selected;
  }
}
featureTabs.forEach((tab, index) => {
  tab.addEventListener("click", () => selectFeature(tab));
  tab.addEventListener("keydown", (event) => {
    let next;
    if (event.key === "ArrowDown") next = featureTabs[(index + 1) % featureTabs.length];
    if (event.key === "ArrowUp") next = featureTabs[(index - 1 + featureTabs.length) % featureTabs.length];
    if (event.key === "Home") next = featureTabs[0];
    if (event.key === "End") next = featureTabs[featureTabs.length - 1];
    if (!next) return;
    event.preventDefault();
    selectFeature(next);
    next.focus();
  });
});
