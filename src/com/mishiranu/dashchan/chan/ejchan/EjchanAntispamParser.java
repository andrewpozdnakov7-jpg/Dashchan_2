package com.mishiranu.dashchan.chan.ejchan;

import android.util.Pair;
import chan.http.RequestEntity;
import chan.text.ParseException;
import chan.text.TemplateParser;
import chan.util.StringUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

final class EjchanAntispamParser {
	private final HashSet<String> ignoreFields = new HashSet<>();
	private final ArrayList<Pair<String, String>> fields = new ArrayList<>();
	private boolean formParsing;
	private String fieldName;
	private String submitValue;

	private EjchanAntispamParser(String source, RequestEntity entity, String... ignoreFields) throws ParseException {
		Collections.addAll(this.ignoreFields, ignoreFields);
		PARSER.parse(source, this);
		for (Pair<String, String> field : fields) {
			entity.add(field.first, field.second);
		}
	}

	public static String parseAndApply(String source, RequestEntity entity, String... ignoreFields)
			throws ParseException {
		return new EjchanAntispamParser(source, entity, ignoreFields).submitValue;
	}

	private static final TemplateParser<EjchanAntispamParser> PARSER =
			TemplateParser.<EjchanAntispamParser>builder()
			.equals("form", "name", "post").open((instance, holder, tagName, attributes) -> {
				holder.formParsing = true;
				return false;
			}).name("input").open((instance, holder, tagName, attributes) -> {
				if (holder.formParsing) {
					String name = attributes.get("name");
					if (name != null) {
						String value = StringUtils.unescapeHtml(
								StringUtils.emptyIfNull(attributes.get("value")));
						if ("post".equals(name)) {
							holder.submitValue = value;
						}
						if (!holder.ignoreFields.contains(name)) {
							holder.fields.add(new Pair<>(name, value));
						}
					}
				}
				return false;
			}).name("textarea").open((instance, holder, tagName, attributes) -> {
				if (holder.formParsing) {
					String name = attributes.get("name");
					if (name != null && !holder.ignoreFields.contains(name)) {
						holder.fieldName = name;
						return true;
					}
				}
				return false;
			}).content((instance, holder, text) -> {
				if (holder.fieldName != null) {
					holder.fields.add(new Pair<>(holder.fieldName, StringUtils.unescapeHtml(text)));
				}
			}).name("textarea").close((instance, holder, tagName) -> holder.fieldName = null)
			.name("form").close((instance, holder, tagName) -> {
				if (holder.formParsing) {
					instance.finish();
				}
			}).prepare();
}
