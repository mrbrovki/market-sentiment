"use strict";

const root = document.getElementById("root");

const candleContext = document.getElementById("candlechart").getContext("2d");
const scoreContext = document.getElementById("scorechart").getContext("2d");
const decayedscoreContext = document
  .getElementById("decayedscorechart")
  .getContext("2d");

candleContext.canvas.width = 1000;
candleContext.canvas.height = 550;

scoreContext.canvas.width = 1000;
scoreContext.canvas.height = 250;

decayedscoreContext.canvas.width = 1000;
decayedscoreContext.canvas.height = 250;

const timeAxis = {
  type: "time",
  time: { unit: "year" },
  offset: false,
  ticks: {
    autoSkip: false,
  },
};

const candleChart = new Chart(candleContext, {
  type: "candlestick",
  data: { datasets: [{ label: "Price", data: [] }] },
  options: {
    animation: false,
    scales: {
      x: { ...timeAxis },
    },
  },
});

const scoreChart = new Chart(scoreContext, {
  type: "scatter",
  data: {
    datasets: [],
  },
  options: {
    animation: true,
    scales: {
      x: { ...timeAxis },
      y: { min: -1, max: 1 },
    },
    plugins: {
      legend: { position: "top" },
      tooltip: {
        callbacks: {
          label: function (context) {
            const point = context.raw;
            return [
              `score: ${point.y.toFixed(3)}`,
              `title: ${point.title}`,
              `url: ${point.url}`,
              `id: ${point.id}`,
              `time: ${new Date(point.x).toLocaleString()}`,
            ];
          },
        },
      },
    },
    showLine: true,
    radius: 4,
  },
});

const decayedScoreChart = new Chart(decayedscoreContext, {
  type: "line",
  data: {
    datasets: [{ label: "Decayed Score", data: [] }],
  },
  options: {
    animation: true,
    scales: {
      x: { ...timeAxis },
      y: { min: -1, max: 1 },
    },
    plugins: {
      legend: { position: "top" },
    },
    showLine: true,
    radius: 4,
  },
});

const assets = ["AAPL", "MSFT", "TSLA", "META"];

const pollPrice = async (asset, startTS, endTS, interval) => {
  const response = await fetch(
    `http://localhost:8989/price/yahoo?asset=${asset}&interval=${interval}&startTS=${startTS}&endTS=${endTS}`
  );

  const obj = await response.json();

  candleChart.data.datasets[0].data = [];

  for (let i = 0; i < obj["results"].length; i++) {
    const o = obj["results"][i]["open"];
    const c = obj["results"][i]["close"];
    const h = obj["results"][i]["high"];
    const l = obj["results"][i]["low"];
    const x = obj["results"][i]["timestamp"] * 1000;
    candleChart.data.datasets[0].data.push({ o, c, h, l, x });
  }
  candleChart.update();
};

pollPrice("TSLA", 1603152000, 1729468800, "1d");
setInterval(() => {
  console.log(scoreChart.data.datasets[0].data[0].x);
  //make in seconds
  const startTs = Math.floor(scoreChart.data.datasets[0].data[0].x / 1000);
  const endTs = Math.floor(Date.now() / 1000);
  pollPrice("TSLA", startTs, endTs, "1d");
}, 10000);

const generateColor = (index) => {
  const colors = ["red", "blue", "green", "purple", "orange", "brown"];
  return colors[index % colors.length];
};

const pollScore = async (asset) => {
  const response = await fetch(
    `http://localhost:8989/assets/${asset}/scores?decayed=false`
  );
  let scores = await response.json();
  scores = scores.sort((a, b) => a.timestamp - b.timestamp);

  const evaluators = [...new Set(scores.map((item) => item.evaluator))];

  scoreChart.data.datasets = evaluators.map((evaluator, index) => ({
    label: `${evaluator.toUpperCase()} Score`,
    data: scores
      .filter((item) => item.evaluator === evaluator)
      .map((item) => ({
        x: item.timestamp,
        y: item.score,
        id: item.id,
        title: item.title,
        url: item.url,
      })),
    backgroundColor: generateColor(index),
    pointRadius: 2,
  }));

  scoreChart.update();
};

pollScore("TSLA");
setInterval(pollScore("TSLA"), 10000);

const pollDecayedScores = async (asset) => {
  const response = await fetch(
    `http://localhost:8989/assets/${asset}/scores?decayed=true`
  );
  let scores = await response.json();
  scores = scores.sort((a, b) => a.timestamp - b.timestamp);
  console.log("Decayed Scores:", scores);
  decayedScoreChart.data.datasets = [{}];
  decayedScoreChart.data.datasets[0].label = "Decayed Score";
  decayedScoreChart.data.datasets[0].data = scores.map((item) => ({
    x: item.timestamp,
    y: item.decayedScore,
  }));
  decayedScoreChart.data.datasets[0].borderColor = "magenta";
  decayedScoreChart.data.datasets[0].backgroundColor = "magenta";

  decayedScoreChart.update();
};

pollDecayedScores("TSLA");
setInterval(pollDecayedScores("TSLA"), 10000);
