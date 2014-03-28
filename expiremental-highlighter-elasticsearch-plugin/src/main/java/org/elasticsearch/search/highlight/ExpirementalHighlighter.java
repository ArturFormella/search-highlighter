package org.elasticsearch.search.highlight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.search.fetch.FetchPhaseExecutionException;
import org.elasticsearch.search.highlight.SearchContextHighlight.FieldOptions;

import expiremental.highlighter.HitEnum;
import expiremental.highlighter.Snippet;
import expiremental.highlighter.SnippetChooser;
import expiremental.highlighter.SnippetFormatter;
import expiremental.highlighter.elasticsearch.SentenceIteratorSegmenterFactory;
import expiremental.highlighter.elasticsearch.CharScanningSegmenterFactory;
import expiremental.highlighter.elasticsearch.ElasticsearchQueryFlattener;
import expiremental.highlighter.elasticsearch.SegmenterFactory;
import expiremental.highlighter.elasticsearch.WholeSourceSegmenterFactory;
import expiremental.highlighter.hit.MergingHitEnum;
import expiremental.highlighter.hit.OverlapMergingHitEnumWrapper;
import expiremental.highlighter.lucene.hit.weight.BasicQueryWeigher;
import expiremental.highlighter.snippet.BasicScoreOrderSnippetChooser;
import expiremental.highlighter.snippet.BasicSourceOrderSnippetChooser;

public class ExpirementalHighlighter implements Highlighter {
    private static final String CACHE_KEY = "highlight-expiremental";

    @Override
    public String[] names() {
        return new String[] { "expiremental" };
    }

    @Override
    public HighlightField highlight(HighlighterContext context) {
        try {
            CacheEntry entry = (CacheEntry) context.hitContext.cache().get(CACHE_KEY);
            if (entry == null) {
                entry = new CacheEntry();
                entry.queryWeigher = new BasicQueryWeigher(new ElasticsearchQueryFlattener(100),
                        context.hitContext.reader(), context.query.originalQuery());
                context.hitContext.cache().put(CACHE_KEY, entry);
            }
            HighlightExecutionContext executionContext = new HighlightExecutionContext(context,
                    entry);
            try {
                return executionContext.highlight();
            } finally {
                executionContext.cleanup();
            }
        } catch (Exception e) {
            throw new FetchPhaseExecutionException(context.context, "Failed to highlight field ["
                    + context.fieldName + "]", e);
        }
    }

    static class CacheEntry {
        BasicQueryWeigher queryWeigher;
    }

    static class HighlightExecutionContext {
        private final HighlighterContext context;
        private final CacheEntry cacheEntry;
        private FieldWrapper defaultField;
        private List<FieldWrapper> extraFields;
        private SegmenterFactory segmenterFactory;

        HighlightExecutionContext(HighlighterContext context, CacheEntry cacheEntry) {
            this.context = context;
            this.cacheEntry = cacheEntry;
            defaultField = new FieldWrapper(this, context, cacheEntry);
        }

        HighlightField highlight() throws IOException {
            List<Snippet> snippets = buildChooser().choose(defaultField.buildSegmenter(),
                    buildHitEnum(), context.field.fieldOptions().numberOfFragments());
            if (snippets.size() == 0) {
                int noMatchSize = context.field.fieldOptions().noMatchSize();
                if (noMatchSize > 0) {
                    List<String> fieldValues = defaultField.getFieldValues();
                    if (fieldValues.size() > 0) {
                        Text fragment = new StringText(getSegmenterFactory()
                                .extractNoMatchFragment(fieldValues.get(0), noMatchSize));
                        return new HighlightField(context.fieldName, new Text[] {fragment});
                    }
                }
                return null;
            }
            return new HighlightField(context.fieldName, formatSnippets(snippets));
        }

        void cleanup() throws Exception {
            Exception lastCaught = null;
            try {
                defaultField.cleanup();
            } catch (Exception e) {
                lastCaught = e;
            }
            if (extraFields != null) {
                for (FieldWrapper extra : extraFields) {
                    try {
                        extra.cleanup();
                    } catch (Exception e) {
                        lastCaught = e;
                    }
                }
            }
            if (lastCaught != null) {
                throw lastCaught;
            }
        }

        SegmenterFactory getSegmenterFactory() {
            if (segmenterFactory == null) {
                segmenterFactory = buildSegmenterFactory();
            }
            return segmenterFactory;
        }

        Object getOption(String key) {
            if (context.field.fieldOptions().options() == null) {
                return null;
            }
            return context.field.fieldOptions().options().get(key);
        }

        /**
         * Builds the hit enum including any required wrappers.
         */
        private HitEnum buildHitEnum() throws IOException {
            // TODO should we be more judicious about this wrapper?
            // * We need the wrapper for matched fields
            // * We need it whenever the analyzer generates overlaps
            // * How much does it actually cost in performance?
            return new OverlapMergingHitEnumWrapper(buildHitFindingHitEnum());
        }

        /**
         * Builds the HitEnum that actually finds the hits in the first place.
         */
        private HitEnum buildHitFindingHitEnum() throws IOException {
            Set<String> matchedFields = context.field.fieldOptions().matchedFields();
            if (matchedFields == null) {
                return defaultField.buildHitEnum();
            }
            List<HitEnum> toMerge = new ArrayList<HitEnum>(matchedFields.size());
            extraFields = new ArrayList<FieldWrapper>(matchedFields.size());
            for (String field : matchedFields) {
                FieldWrapper wrapper;
                if (context.fieldName.equals(field)) {
                    wrapper = defaultField;
                } else {
                    wrapper = new FieldWrapper(this, context, cacheEntry, field);
                }
                toMerge.add(wrapper.buildHitEnum());
                extraFields.add(wrapper);
            }
            return new MergingHitEnum(toMerge, HitEnum.LessThans.OFFSETS);
        }

        private SnippetChooser buildChooser() {
            if (context.field.fieldOptions().scoreOrdered()) {
                return new BasicScoreOrderSnippetChooser();
            }
            return new BasicSourceOrderSnippetChooser();
        }

        private Text[] formatSnippets(List<Snippet> snippets) throws IOException {
            SnippetFormatter formatter = new SnippetFormatter(defaultField.buildSourceExtracter(),
                    context.field.fieldOptions().preTags()[0], context.field.fieldOptions()
                            .postTags()[0]);
            Text[] result = new Text[snippets.size()];
            int i = 0;
            for (Snippet snippet : snippets) {
                result[i++] = new StringText(formatter.format(snippet));
            }
            return result;
        }

        private SegmenterFactory buildSegmenterFactory() {
            FieldOptions options = context.field.fieldOptions();
            if (options.fragmenter() == null || options.fragmenter().equals("scan")) {
                // TODO boundaryChars
                return new CharScanningSegmenterFactory(options.fragmentCharSize(),
                        options.boundaryMaxScan());
            }
            if (options.fragmenter().equals("sentence")) {
                String localeString = (String) getOption("locale");
                Locale locale;
                if (localeString == null) {
                    locale = Locale.US;
                } else {
                    locale = Strings.parseLocaleString(localeString);
                }
                return new SentenceIteratorSegmenterFactory(locale, options.boundaryMaxScan());
            }
            if (options.fragmenter().equals("none")) {
                return new WholeSourceSegmenterFactory();
            }
            throw new IllegalArgumentException("Unknown fragmenter:  '" + options.fragmenter()
                    + "'.  Options are 'scan' or 'sentence'.");
        }
    }
}
