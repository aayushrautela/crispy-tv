// Reference plugin: Nuvio-compatible JS scraper plugin for Crispy.
// Contract: exports (or defines globally) `getStreams(input)` returning
// Promise<{ streams: [...] } | Array> with the stream fields:
//   name, url, quality?, headers?, referer?, subtitles?, sizeBytes?, audio?, filename?
// Host provides: fetch, URL/URLSearchParams, atob/btoa, CryptoJS, cheerio,
// TextEncoder/TextDecoder, crispy.storage, SCRAPER_ID, SCRAPER_SETTINGS.

async function getStreams(input) {
  const mediaType = input.mediaType || 'movie';
  const imdbId = input.imdbId || '';
  const season = input.season;
  const episode = input.episode;

  const streams = [];

  if (mediaType === 'movie' && imdbId && input.tmdbId > 0) {
    streams.push({
      name: 'Reference HD',
      url: 'https://cdn.crispy.tv/reference/' + encodeURIComponent('' + input.tmdbId) + '.mp4',
      quality: '1080p',
      referer: 'https://cinemeta-curtains.wixsite.com',
      headers: { 'User-Agent': 'CrispyReferencePlugin/1.0' },
      filename: imdbId + '.mp4',
    });
  }

  if (mediaType === 'series' && imdbId && season && episode) {
    streams.push({
      name: 'Reference S' + season + 'E' + episode,
      url: 'https://cdn.crispy.tv/reference/' +
        encodeURIComponent(imdbId) + '/' + season + '/' + episode + '.m3u8',
      quality: '720p',
      subtitles: [{ url: 'https://cdn.crispy.tv/reference/subs.vtt', lang: 'en' }],
    });
  }

  return { streams };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { getStreams };
}
