package com.mishiranu.dashchan.content.storage;

import chan.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AutoBumpStorage extends StorageManager.JsonOrgStorage<List<AutoBumpStorage.Task>> {
	private static final String KEY_TASKS = "tasks";
	public static final int MAX_TASKS = 10;
	public static final int MAX_CONSECUTIVE_FAILURES = 5;
	public static final long MAX_NEXT_NUMBER = 2_000L;
	public static final long MIN_INTERVAL_MINUTES = 15L;
	public static final long MAX_INTERVAL_MINUTES = 525_600L;

	private static final AutoBumpStorage INSTANCE = new AutoBumpStorage();

	public static AutoBumpStorage getInstance() {
		return INSTANCE;
	}

	public static class Task {
		public String id;
		public String chanName;
		public String boardName;
		public String threadNumber;
		public String title;
		public String message;
		public long nextNumber;
		public long intervalMinutes;
		public long nextRunAt;
		public long lastActivityAt;
		public String lastBumpPostNumber;
		public int consecutiveFailures;
		public String lastError;
		public boolean enabled;
		public boolean completed;

		public Task() {}

		public Task(Task task) {
			id = task.id;
			chanName = task.chanName;
			boardName = task.boardName;
			threadNumber = task.threadNumber;
			title = task.title;
			message = task.message;
			nextNumber = task.nextNumber;
			intervalMinutes = task.intervalMinutes;
			nextRunAt = task.nextRunAt;
			lastActivityAt = task.lastActivityAt;
			lastBumpPostNumber = task.lastBumpPostNumber;
			consecutiveFailures = task.consecutiveFailures;
			lastError = task.lastError;
			enabled = task.enabled;
			completed = task.completed;
		}

		public String formatMessage() {
			String source = StringUtils.emptyIfNull(message).trim();
			String number = Long.toString(Math.max(1L, nextNumber));
			return source.contains("{n}") ? source.replace("{n}", number)
					: source + (source.isEmpty() ? "" : " ") + number;
		}

		private JSONObject serialize() throws JSONException {
			JSONObject json = new JSONObject();
			json.put("id", id);
			json.put("chanName", chanName);
			json.put("boardName", boardName);
			json.put("threadNumber", threadNumber);
			json.put("title", title);
			json.put("message", message);
			json.put("nextNumber", nextNumber);
			json.put("intervalMinutes", intervalMinutes);
			json.put("nextRunAt", nextRunAt);
			json.put("lastActivityAt", lastActivityAt);
			json.put("lastBumpPostNumber", lastBumpPostNumber);
			json.put("consecutiveFailures", consecutiveFailures);
			json.put("lastError", lastError);
			json.put("enabled", enabled);
			json.put("completed", completed);
			return json;
		}

		private static Task deserialize(JSONObject json) {
			Task task = new Task();
			task.id = json.optString("id");
			task.chanName = json.optString("chanName");
			task.boardName = json.optString("boardName");
			task.threadNumber = json.optString("threadNumber");
			task.title = json.optString("title");
			task.message = json.optString("message");
			task.nextNumber = clampNextNumber(json.optLong("nextNumber", 1L));
			task.intervalMinutes = clampIntervalMinutes(json.optLong("intervalMinutes", MIN_INTERVAL_MINUTES));
			long now = System.currentTimeMillis();
			task.nextRunAt = json.optLong("nextRunAt", calculateNextRunAt(now, task.intervalMinutes));
			long maximumNextRunAt = calculateNextRunAt(now, task.intervalMinutes);
			if (task.nextRunAt <= 0L || task.nextRunAt > maximumNextRunAt) {
				task.nextRunAt = maximumNextRunAt;
			}
			long defaultLastActivityAt = Math.max(0L,
					task.nextRunAt - task.intervalMinutes * 60_000L);
			task.lastActivityAt = Math.min(now, Math.max(0L,
					json.optLong("lastActivityAt", defaultLastActivityAt)));
			task.lastBumpPostNumber = StringUtils.nullIfEmpty(json.optString("lastBumpPostNumber", null));
			task.consecutiveFailures = Math.max(0, json.optInt("consecutiveFailures"));
			task.lastError = json.optString("lastError", null);
			task.enabled = json.optBoolean("enabled", true);
			task.completed = json.optBoolean("completed", false);
			if (task.completed) {
				task.enabled = false;
			}
			return task;
		}
	}

	private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();

	private AutoBumpStorage() {
		super("auto_bump", 500, 5000);
		startRead();
	}

	@Override
	public synchronized List<Task> onClone() {
		ArrayList<Task> result = new ArrayList<>(tasks.size());
		for (Task task : tasks.values()) {
			result.add(new Task(task));
		}
		return result;
	}

	@Override
	public synchronized void onDeserialize(JSONObject jsonObject) {
		JSONArray array = jsonObject.optJSONArray(KEY_TASKS);
		if (array == null) {
			return;
		}
		for (int i = 0; i < array.length(); i++) {
			JSONObject json = array.optJSONObject(i);
			if (json == null) {
				continue;
			}
			Task task = Task.deserialize(json);
			if (!StringUtils.isEmpty(task.id) && !StringUtils.isEmpty(task.chanName)
					&& !StringUtils.isEmpty(task.boardName) && !StringUtils.isEmpty(task.threadNumber)) {
				tasks.put(task.id, task);
			}
		}
	}

	@Override
	public JSONObject onSerialize(List<Task> tasks) throws JSONException {
		JSONArray array = new JSONArray();
		for (Task task : tasks) {
			array.put(task.serialize());
		}
		return new JSONObject().put(KEY_TASKS, array);
	}

	public synchronized List<Task> getTasks() {
		return onClone();
	}

	public synchronized Task getTask(String id) {
		Task task = tasks.get(id);
		return task != null ? new Task(task) : null;
	}

	public synchronized Task findTask(String chanName, String boardName, String threadNumber) {
		for (Task task : tasks.values()) {
			if (task.chanName.equals(chanName) && task.boardName.equals(boardName)
					&& task.threadNumber.equals(threadNumber)) {
				return new Task(task);
			}
		}
		return null;
	}

	public synchronized boolean put(Task task) {
		if (StringUtils.isEmpty(task.id)) {
			if (tasks.size() >= MAX_TASKS) {
				return false;
			}
			task.id = UUID.randomUUID().toString();
		}
		task.nextNumber = clampNextNumber(task.nextNumber);
		task.intervalMinutes = clampIntervalMinutes(task.intervalMinutes);
		if (task.completed) {
			task.enabled = false;
		}
		if (task.nextRunAt <= 0L) {
			task.nextRunAt = calculateNextRunAt(System.currentTimeMillis(), task.intervalMinutes);
		}
		tasks.put(task.id, new Task(task));
		serialize();
		return true;
	}

	public synchronized void remove(String id) {
		if (tasks.remove(id) != null) {
			serialize();
		}
	}

	public synchronized void updateAfterSuccess(String id, long now, String postNumber) {
		Task task = tasks.get(id);
		if (task != null) {
			task.lastActivityAt = now;
			if (!StringUtils.isEmpty(postNumber)) {
				task.lastBumpPostNumber = postNumber;
			} else {
				task.lastBumpPostNumber = null;
			}
			if (task.nextNumber < MAX_NEXT_NUMBER) {
				task.nextNumber++;
			} else {
				task.enabled = false;
				task.completed = true;
			}
			task.consecutiveFailures = 0;
			task.lastError = null;
			task.nextRunAt = calculateNextRunAt(now, task.intervalMinutes);
			serialize();
		}
	}

	public synchronized void updateAfterThreadActivity(String id, String postNumber, long activityAt) {
		Task task = tasks.get(id);
		if (task != null) {
			task.lastActivityAt = Math.max(task.lastActivityAt, activityAt);
			if (!StringUtils.isEmpty(postNumber)) {
				task.lastBumpPostNumber = postNumber;
			}
			task.consecutiveFailures = 0;
			task.lastError = null;
			task.nextRunAt = calculateNextRunAt(task.lastActivityAt, task.intervalMinutes);
			serialize();
		}
	}

	public synchronized boolean updateAfterManualPost(String chanName, String boardName, String threadNumber,
			String postNumber, long now) {
		boolean changed = false;
		for (Task task : tasks.values()) {
			if (task.enabled && task.chanName.equals(chanName) && task.boardName.equals(boardName)
					&& task.threadNumber.equals(threadNumber)) {
				task.lastActivityAt = now;
				if (!StringUtils.isEmpty(postNumber)) {
					task.lastBumpPostNumber = postNumber;
				} else {
					task.lastBumpPostNumber = null;
				}
				task.consecutiveFailures = 0;
				task.lastError = null;
				task.nextRunAt = calculateNextRunAt(now, task.intervalMinutes);
				changed = true;
			}
		}
		if (changed) {
			serialize();
		}
		return changed;
	}

	public synchronized Task updateAfterFailure(String id, String error, boolean permanent, long now) {
		Task task = tasks.get(id);
		if (task == null) {
			return null;
		}
		task.consecutiveFailures++;
		task.lastError = error;
		task.nextRunAt = calculateNextRunAt(now, task.intervalMinutes);
		if (permanent || task.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
			task.enabled = false;
		}
		serialize();
		return new Task(task);
	}

	public synchronized void scheduleNow(String id) {
		Task task = tasks.get(id);
		if (task != null && task.enabled) {
			task.consecutiveFailures = 0;
			task.lastError = null;
			task.nextRunAt = System.currentTimeMillis();
			serialize();
		}
	}

	public synchronized void postpone(String id, long nextRunAt) {
		Task task = tasks.get(id);
		if (task != null) {
			task.nextRunAt = nextRunAt;
			serialize();
		}
	}

	public static long clampIntervalMinutes(long intervalMinutes) {
		return Math.max(MIN_INTERVAL_MINUTES, Math.min(intervalMinutes, MAX_INTERVAL_MINUTES));
	}

	public static long clampNextNumber(long nextNumber) {
		return Math.max(1L, Math.min(nextNumber, MAX_NEXT_NUMBER));
	}

	public static long calculateNextRunAt(long now, long intervalMinutes) {
		long delay = clampIntervalMinutes(intervalMinutes) * 60_000L;
		return now <= Long.MAX_VALUE - delay ? now + delay : Long.MAX_VALUE;
	}

	public static long calculateRemainingMinutes(long nextRunAt, long now) {
		if (nextRunAt <= now) {
			return 0L;
		}
		long remaining = nextRunAt - now;
		return (remaining - 1L) / 60_000L + 1L;
	}
}
