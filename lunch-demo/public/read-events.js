export async function readEvents(reply, onEvent) {
  let buffer = '';
  function consume(chunk) {
    const lines = (buffer + chunk).split('\n');
    buffer = lines.pop();
    for (const line of lines) {
      if (line.trim()) onEvent(JSON.parse(line));
    }
  }

  if (typeof reply.body?.getReader !== 'function' || typeof TextDecoder !== 'function') {
    consume(await reply.text());
  } else {
    const reader = reply.body.getReader();
    const decoder = new TextDecoder();
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        consume(decoder.decode(value, { stream: true }));
      }
      consume(decoder.decode());
    } finally {
      reader.releaseLock();
    }
  }
  consume('\n');
}
