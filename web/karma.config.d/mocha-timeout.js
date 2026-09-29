// Async tests are awaited by Mocha, whose 2s default is too short for tests that load and render
// many assets in sequence (e.g. MeshRendererTests walks every verified item model).
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
