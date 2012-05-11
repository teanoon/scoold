/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.scoold.db.cassandra;

import com.scoold.core.Language;
import com.scoold.core.Translation;
import com.scoold.db.AbstractDAOFactory;
import com.scoold.db.AbstractTranslationDAO;
import com.scoold.db.cassandra.CasDAOFactory.Column;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.MultigetSliceQuery;
import org.apache.commons.lang.LocaleUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.mutable.MutableLong;

/**
 *
 * @author alexb
 */
final class CasTranslationDAO<T, PK> extends AbstractTranslationDAO<Translation, Long>{

	private static final Logger logger = Logger.getLogger(CasTranslationDAO.class.getName());
	private CasDAOUtils cdu = (CasDAOUtils) CasDAOFactory.getInstance().getDAOUtils();
//	private String cacheKey = "Language" + AbstractDAOFactory.SEPARATOR;

	public CasTranslationDAO() { }

	public Translation read(Long id) {
		return cdu.read(Translation.class, id.toString());
	}

	public Long create(Translation newInstance) {
		if(newInstance.getUserid() == null ||
				StringUtils.isBlank(newInstance.getKey()) ||
				StringUtils.isBlank(newInstance.getValue()) ||
				StringUtils.isBlank(newInstance.getLocale()))
			return null;

		Mutator<String> mut = cdu.createMutator();
		Long id = cdu.create(newInstance, mut);

		if(id != null){
			String compositeKey = newInstance.getLocale().
					concat(AbstractDAOFactory.SEPARATOR).concat(newInstance.getKey());

			cdu.addNumbersortColumn(compositeKey, CasDAOFactory.LOCALES_TRANSLATIONS,
					id, newInstance.getVotes(), null, mut);
		}

		mut.execute();

		return id;
	}

	public void update(Translation transientObject) {
		String compositeKey = transientObject.getLocale().
					concat(AbstractDAOFactory.SEPARATOR).concat(transientObject.getKey());

		Mutator<String> mut = cdu.createMutator();

		cdu.addNumbersortColumn(compositeKey, CasDAOFactory.LOCALES_TRANSLATIONS,
					transientObject.getId(), transientObject.getVotes(),
					transientObject.getOldvotes(), mut);

		cdu.update(transientObject, mut);
		
		mut.execute();
	}

	public void delete(Translation persistentObject) {
		Long id = persistentObject.getId();
		String compositeKey = persistentObject.getLocale().
					concat(AbstractDAOFactory.SEPARATOR).concat(persistentObject.getKey());

		Mutator<String> mut = cdu.createMutator();

		// delete post
		cdu.delete(persistentObject, mut);
		cdu.removeNumbersortColumn(compositeKey, CasDAOFactory.LOCALES_TRANSLATIONS, id,
				persistentObject.getVotes(), mut);

		mut.execute();
	}

	public ArrayList<Translation> readAllTranslationsForKey(String locale, String key,
			MutableLong pagenum, MutableLong itemcount){

		String compositeKey = locale.concat(AbstractDAOFactory.SEPARATOR).concat(key);

		String startKey = null;
		boolean isPageValid = pagenum != null && pagenum.longValue() > 1;

		if (isPageValid) {
			String votes = cdu.getColumn(pagenum.toString(), CasDAOFactory.OBJECTS, "votes");
			if(votes != null){
				startKey = votes.concat(AbstractDAOFactory.SEPARATOR)
						.concat(pagenum.toString());
			}
		}

		return cdu.readAll(Translation.class, null, compositeKey, 
				CasDAOFactory.LOCALES_TRANSLATIONS,
				String.class, startKey, pagenum, itemcount,
				CasDAOFactory.MAX_ITEMS_PER_PAGE, true, false, true);
	}

	public Map<String, Integer> readTranslationCountForAllKeys(String locale,
			ArrayList<String> keys){
		Map<String, Integer> map = new HashMap<String, Integer>();

		ArrayList<String> keyz = new ArrayList<String>();
		for (String key : keys) {
			keyz.add(locale.concat(AbstractDAOFactory.SEPARATOR).concat(key));
			map.put(key, 0);
		}
		
		Serializer<String> strser = cdu.getSerializer(String.class);		
		
		MultigetSliceQuery<String, String, String> q =
				HFactory.createMultigetSliceQuery(cdu.getKeyspace(), strser, strser, strser);

		q.setColumnFamily(CasDAOFactory.LOCALES_TRANSLATIONS.getName());
		q.setKeys(keyz.toArray(new String[]{}));
		q.setRange(null, null, false, CasDAOFactory.DEFAULT_LIMIT);

		for (Row<String, String, String> row : q.execute().get()) {
			String rk = row.getKey();
			String rowKey = rk.substring(rk.indexOf(AbstractDAOFactory.SEPARATOR) + 1);
			map.put(rowKey, row.getColumnSlice().getColumns().size());
		}

		return map;
	}

	public void approve(Translation t) {
		if(t != null && !StringUtils.isBlank(t.getValue())){
			cdu.batchPut(
				new Column(t.getLocale(), CasDAOFactory.LANGUAGE,
					t.getKey(), t.getValue()),
				new Column(t.getLocale(), CasDAOFactory.APPROVED_TRANSLATIONS,
					t.getKey(), t.getId().toString())
			);
		}
	}

	public void disapprove(Translation t) {
		if(t != null && !StringUtils.isBlank(t.getValue())){
			cdu.batchRemove(
				new Column(t.getLocale(), CasDAOFactory.LANGUAGE, t.getKey(), null),
				new Column(t.getLocale(), CasDAOFactory.APPROVED_TRANSLATIONS, t.getKey(), null)
			);
		}
	}

	public Map<String, Long> readApprovedIdsForLocale(String locale){
		if (StringUtils.isBlank(locale) || "en".equals(locale)) return null;

		Map<String, Long> map = new HashMap<String, Long>();

		List<HColumn<String, String>> cols = cdu.readRow(locale,
				CasDAOFactory.APPROVED_TRANSLATIONS, String.class,
				null, null, null, CasDAOFactory.DEFAULT_LIMIT, false);

		for (HColumn<String, String> hColumn : cols) {
			map.put(hColumn.getName(), NumberUtils.toLong(hColumn.getValue()));
		}

		return map;
	}

	public Map<String, Integer> calculateProgressForAll(int total){
		Map<String, Integer> map = new HashMap<String, Integer>();

		ArrayList<String> allocs = new ArrayList<String> ();
		for (Object loc : LocaleUtils.availableLocaleList()) {
			Locale locale = (Locale) loc;
			String locstr = locale.getLanguage();
			if(!StringUtils.isBlank(locstr)){
				allocs.add(locstr);
			}
		}
		
		Serializer<String> strser = cdu.getSerializer(String.class);

		MultigetSliceQuery<String, String, String> q =
			HFactory.createMultigetSliceQuery(cdu.getKeyspace(), strser, strser, strser);

		q.setColumnFamily(CasDAOFactory.APPROVED_TRANSLATIONS.getName());
		q.setKeys(allocs.toArray(new String[]{}));
		q.setRange(null, null, false, CasDAOFactory.DEFAULT_LIMIT);

		for (Row<String, String, String> row : q.execute().get()) {
			String key = row.getKey();
			if("en".equals(key)){
				map.put(key, 100);
			}else{
				double cols = row.getColumnSlice().getColumns().size();
				double count = (cols / (double) total) * 100;
				map.put(key, (int) Math.round(count));
			}
		}

		return map;
	}

	public void disapproveAllForKey(String key) {
		Mutator<String> mut = cdu.createMutator();
		for (String locstr : Language.ALL_LOCALES.keySet()) {
			cdu.addDeletion(new Column<String, String>(locstr, CasDAOFactory.LANGUAGE, 
					key, null), mut);
			cdu.addDeletion(new Column<String, String>(locstr, CasDAOFactory.APPROVED_TRANSLATIONS, 
					key, null), mut);
		}

		mut.execute();
	}
	
	public void disapproveAllForKey(String key, String locale) {
		Mutator<String> mut = cdu.createMutator();
		cdu.addDeletion(new Column<String, String>(locale, CasDAOFactory.LANGUAGE, 
				key, null), mut);
		cdu.addDeletion(new Column<String, String>(locale, CasDAOFactory.APPROVED_TRANSLATIONS, 
				key, null), mut);
		mut.execute();
	}

	public Map<String, String> readLanguage(Locale locale) {
		Map<String, String> deflang = Language.getDefaultLanguage();
		String lang = locale == null ? "en" : locale.getLanguage();
		if(lang.equals("en")) return deflang;

		HashMap<String, String> map = new HashMap<String, String>(deflang.size());

		List<HColumn<String, String>> cols = cdu.readRow(lang,
				CasDAOFactory.LANGUAGE, String.class, null, null, null,
				CasDAOFactory.DEFAULT_LIMIT, false);

		map.putAll(deflang);

		for (HColumn<String, String> row : cols) {
			map.put(row.getName(), row.getValue());
		}

		return map;
	}
}
