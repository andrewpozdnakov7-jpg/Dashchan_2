/* This file is part of Slooop and uses Mozilla Bergamot under MPL-2.0. */
(function () {
	"use strict";

	var bergamot = null;
	var model = null;
	var service = null;

	function fetchBuffer(path) {
		return fetch(path, {cache: "no-store"}).then(function (response) {
			if (!response.ok) throw new Error("Cannot load " + path + ": HTTP " + response.status);
			return response.arrayBuffer();
		});
	}

	function canonicalizeHtml(html) {
		var template = document.createElement("template");
		template.innerHTML = html;
		return template.innerHTML;
	}

	function alignedMemory(buffer, alignment) {
		var memory = new bergamot.AlignedMemory(buffer.byteLength, alignment);
		memory.getByteArrayView().set(new Uint8Array(buffer));
		return memory;
	}

	function configText() {
		return "\n" +
			" beam-size: 1\n" +
			" normalize: 1.0\n" +
			" word-penalty: 0\n" +
			" max-length-break: 128\n" +
			" mini-batch-words: 1024\n" +
			" workspace: 128\n" +
			" max-length-factor: 2.0\n" +
			" skip-cost: true\n" +
			" cpu-threads: 0\n" +
			" quiet: true\n" +
			" quiet-translation: true\n" +
			" gemm-precision: int8shiftAlphaAll\n" +
			" alignment: soft\n ";
	}

	function loadRuntime(wasmBuffer) {
		return new Promise(function (resolve, reject) {
			var instance = loadBergamot({
				INITIAL_MEMORY: 41943040,
				wasmBinary: wasmBuffer,
				print: function () {},
				printErr: function () {},
				onAbort: function () { reject(new Error("Bergamot runtime aborted")); },
				onRuntimeInitialized: function () {
					Promise.resolve().then(function () { resolve(instance); });
				}
			});
		});
	}

	function initialize(sourceLanguage, targetLanguage) {
		Promise.all([
			fetchBuffer("/runtime/bergamot-translator.wasm"),
			fetchBuffer("/model/model.bin"),
			fetchBuffer("/model/lex.bin"),
			fetchBuffer("/model/vocab.spm")
		]).then(function (buffers) {
			return loadRuntime(buffers[0]).then(function (runtime) {
				bergamot = runtime;
				var modelMemory = alignedMemory(buffers[1], 256);
				var lexMemory = alignedMemory(buffers[2], 64);
				var vocabMemory = alignedMemory(buffers[3], 64);
				var vocabList = new bergamot.AlignedMemoryList();
				vocabList.push_back(vocabMemory);
				model = new bergamot.TranslationModel(sourceLanguage, targetLanguage, configText(),
					modelMemory, lexMemory, vocabList, null);
				service = new bergamot.BlockingService({cacheSize: 0});
				SlooopTranslation.onReady();
			});
		}).catch(function (error) {
			SlooopTranslation.onInitializationError(String(error && error.message || error));
		});
	}

	function translate(requestId, subject, html) {
		if (!service || !model) {
			SlooopTranslation.onTranslationError(String(requestId), "Translator is not ready");
			return;
		}
		if (!subject && !html) {
			SlooopTranslation.onTranslationResult(String(requestId), "", "");
			return;
		}
		var messages = new bergamot.VectorString();
		var options = new bergamot.VectorResponseOptions();
		var responses = null;
		var responseObjects = [];
		var subjectIndex = -1;
		var commentIndex = -1;
		try {
			if (subject) {
				subjectIndex = messages.size();
				messages.push_back(subject);
				options.push_back({qualityScores: false, alignment: true, html: false});
			}
			if (html) {
				commentIndex = messages.size();
				// Decode character references before Bergamot sees the HTML. Otherwise the engine can preserve
				// an entity such as &#039; as escaped text, which is then displayed literally by the post parser.
				messages.push_back(canonicalizeHtml(html));
				options.push_back({qualityScores: false, alignment: true, html: true});
			}
			responses = service.translate(model, messages, options);
			var translatedSubject = "";
			var translatedHtml = "";
			if (subjectIndex >= 0) {
				var subjectResponse = responses.get(subjectIndex);
				responseObjects.push(subjectResponse);
				translatedSubject = subjectResponse.getTranslatedText();
			}
			if (commentIndex >= 0) {
				var commentResponse = responses.get(commentIndex);
				responseObjects.push(commentResponse);
				translatedHtml = commentResponse.getTranslatedText();
			}
			SlooopTranslation.onTranslationResult(String(requestId), translatedSubject, translatedHtml);
		} catch (error) {
			SlooopTranslation.onTranslationError(String(requestId), String(error && error.message || error));
		} finally {
			responseObjects.forEach(function (response) {
				if (response && response.delete) response.delete();
			});
			if (responses && responses.delete) responses.delete();
			messages.delete();
			options.delete();
		}
	}

	window.SlooopBergamot = {initialize: initialize, translate: translate};
})();
