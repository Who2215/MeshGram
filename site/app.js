(function () {
  const canvas = document.getElementById('starfield');
  if (!canvas) return;
  const context = canvas.getContext('2d');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let stars = [];
  let width = 0;
  let height = 0;
  let pixelRatio = 1;

  function resize() {
    pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    width = window.innerWidth;
    height = window.innerHeight;
    canvas.width = width * pixelRatio;
    canvas.height = height * pixelRatio;
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    const count = Math.min(150, Math.max(65, Math.floor(width * height / 13000)));
    stars = Array.from({ length: count }, (_, index) => ({
      x: Math.random() * width,
      y: Math.random() * height,
      radius: Math.random() * 1.5 + .25,
      alpha: Math.random() * .65 + .15,
      phase: Math.random() * Math.PI * 2,
      speed: Math.random() * .0006 + .00015,
      tint: index % 7 === 0 ? 'cyan' : index % 11 === 0 ? 'pink' : 'white'
    }));
  }

  function draw(time) {
    context.clearRect(0, 0, width, height);
    stars.forEach((star) => {
      const pulse = reduceMotion.matches ? 1 : .72 + Math.sin(time * star.speed + star.phase) * .28;
      const alpha = star.alpha * pulse;
      const color = star.tint === 'cyan' ? `82,231,255` : star.tint === 'pink' ? `243,91,216` : `222,231,255`;
      context.beginPath();
      context.fillStyle = `rgba(${color},${alpha})`;
      context.shadowBlur = star.radius > 1 ? 10 : 0;
      context.shadowColor = `rgba(${color},${alpha})`;
      context.arc(star.x, star.y, star.radius * (star.tint === 'white' ? 1 : 1.2), 0, Math.PI * 2);
      context.fill();
    });
    context.shadowBlur = 0;
    if (!reduceMotion.matches) requestAnimationFrame(draw);
  }

  resize();
  window.addEventListener('resize', resize, { passive: true });
  requestAnimationFrame(draw);
})();
