package yuku.alkitab.base.cp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import yuku.afw.App;
import yuku.alkitab.base.S;
import yuku.alkitab.base.U;
import yuku.alkitab.base.ac.VersionsActivity.MVersionPreset;
import yuku.alkitab.base.ac.VersionsActivity.MVersionYes;
import yuku.alkitab.base.config.AppConfig;
import yuku.alkitab.base.model.Ari;
import yuku.alkitab.base.model.Book;
import yuku.alkitab.base.util.IntArrayList;
import yuku.alkitab.base.util.LidToAri;

public class Provider extends ContentProvider {
	public static final String TAG = Provider.class.getSimpleName();

	private static final String AUTHORITY = "yuku.alkitab.provider";
	private static final int PATH_bible_verses_single_by_lid = 1;
	private static final int PATH_bible_verses_single_by_ari = 2;
	private static final int PATH_bible_verses_range_by_lid = 3;
	private static final int PATH_bible_verses_range_by_ari = 4;
	private static final int PATH_bible_versions = 5;
	
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
    	Log.d(TAG, Provider.class.getName() + " @@static_init");
    	
    	uriMatcher.addURI(AUTHORITY, "bible/verses/single/by-lid/#", PATH_bible_verses_single_by_lid); 
    	uriMatcher.addURI(AUTHORITY, "bible/verses/single/by-ari/#", PATH_bible_verses_single_by_ari); 
    	uriMatcher.addURI(AUTHORITY, "bible/verses/range/by-lid/*", PATH_bible_verses_range_by_lid); 
    	uriMatcher.addURI(AUTHORITY, "bible/verses/range/by-ari/*", PATH_bible_verses_range_by_ari); 
    	uriMatcher.addURI(AUTHORITY, "bible/versions", PATH_bible_versions); 
    }

    @Override public boolean onCreate() {
    	Log.d(TAG, "@@onCreate");
    	
    	yuku.afw.App.initWithAppContext(getContext().getApplicationContext());
    	yuku.alkitab.base.App.staticInit();
    	
		return true;
	}

	@Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		Log.d(TAG, "@@query uri=" + uri + " projection=" + Arrays.toString(projection) + " selection=" + selection + " args=" + Arrays.toString(selectionArgs) + " sortOrder=" + sortOrder);
		
		int uriMatch = uriMatcher.match(uri);
		Log.d(TAG, "uriMatch=" + uriMatch);
		
		String formatting_s = uri.getQueryParameter("formatting");
		boolean formatting = parseBoolean(formatting_s);
		
		Cursor res;
		
		switch (uriMatch) {
		case PATH_bible_verses_single_by_lid: {
			res = getCursorForSingleVerseLid(parseInt(uri.getLastPathSegment(), Integer.MIN_VALUE), formatting);
		} break;
		case PATH_bible_verses_single_by_ari: {
			res = getCursorForSingleVerseAri(parseInt(uri.getLastPathSegment(), Integer.MIN_VALUE), formatting);
		} break;
		case PATH_bible_verses_range_by_lid: {
			String range = uri.getLastPathSegment();
			IntArrayList lids = decodeLidRange(range);
			res = getCursorForRangeVerseLid(lids, formatting);
		} break;
		case PATH_bible_verses_range_by_ari: {
			String range = uri.getLastPathSegment();
			IntArrayList aris = decodeAriRange(range);
			res = getCursorForRangeVerseAri(aris, formatting);
		} break;
		case PATH_bible_versions: {
			res = getCursorForBibleVersions();
		} break;
		default: {
			res = null;
		} break;
		}
		
		Log.d(TAG, "returning " + (res == null? "null": "cursor with " + res.getCount() + " rows"));
		return res;
	}

	/**
	 * @return [start, end, start, end, ...]
	 */
	private IntArrayList decodeLidRange(String range) {
		IntArrayList res = new IntArrayList();
		
		String[] splits = range.split(",");
		for (String split: splits) {
			int start, end;
			if (split.indexOf('-') != -1) {
				String[] startEnd = split.split("-", 2);
				start = parseInt(startEnd[0], Integer.MIN_VALUE);
				end = parseInt(startEnd[1], Integer.MIN_VALUE);
			} else {
				start = end = parseInt(split, Integer.MIN_VALUE);
			}
			
			if (start != Integer.MIN_VALUE && end != Integer.MIN_VALUE) {
				res.add(start);
				res.add(end);
			}
		}
		
		return res;
	}
	
	/**
	 * Also supports verse 0 for the whole chapter (0xbbcc00)
	 * 
	 * @return [start, end, start, end, ...]
	 */
	private IntArrayList decodeAriRange(String range) {
		IntArrayList res = new IntArrayList();
		
		String[] splits = range.split(",");
		for (String split: splits) {
			int start, end;
			if (split.indexOf('-') != -1) {
				String[] startEnd = split.split("-", 2);
				start = parseInt(startEnd[0], Integer.MIN_VALUE);
				end = parseInt(startEnd[1], Integer.MIN_VALUE);
			} else {
				start = end = parseInt(split, Integer.MIN_VALUE);
			}
			
			if (start != Integer.MIN_VALUE && end != Integer.MIN_VALUE) {
				start &= 0xffffff;
				end &= 0xffffff;
				
				if (start == end && Ari.toVerse(start) == 0) {
					// case: 0xXXYY00 - 0xXXYY00 (whole single chapter)
					res.add(start | 0x01);
					res.add(start | 0xff);
				} else if (end >= start) {
					if (Ari.toVerse(start) == 0) {
						start = start | 0x01;
					}
					if (Ari.toVerse(end) == 0) {
						end = end | 0xff;
					}
					res.add(start);
					res.add(end);
				}
			}
		}
		
		return res;
	}

	private Cursor getCursorForSingleVerseLid(int lid, boolean formatting) {
		int ari = LidToAri.lidToAri(lid);
		return getCursorForSingleVerseAri(ari, formatting);
	}

	private Cursor getCursorForSingleVerseAri(int ari, boolean formatting) {
		MatrixCursor res = new MatrixCursor(new String[] {"_id", "ari", "bookName", "text"});
		
		Log.d(TAG, "getting ari 0x" + Integer.toHexString(ari));
		
		if (ari != Integer.MIN_VALUE && ari != 0) {
			Book book = S.activeVersion.getBook(Ari.toBook(ari));
			if (book != null) {
				String text = S.loadVerseText(S.activeVersion, ari);
				if (formatting == false) {
					text = U.removeSpecialCodes(text);
				}
				res.addRow(new Object[] {1, ari, book.judul, text});
			}
		}
		
		return res;
	}
	
	private Cursor getCursorForRangeVerseLid(IntArrayList lids, boolean formatting) {
		IntArrayList aris = new IntArrayList(lids.size());
		for (int i = 0, len = lids.size(); i < len; i+=2) {
			int lid_start = lids.get(i);
			int lid_end = lids.get(i + 1);
			int ari_start = LidToAri.lidToAri(lid_start);
			int ari_end = LidToAri.lidToAri(lid_end);
			aris.add(ari_start);
			aris.add(ari_end);
		}
		
		return getCursorForRangeVerseAri(aris, formatting);
	}

	private Cursor getCursorForRangeVerseAri(IntArrayList aris, boolean formatting) {
		MatrixCursor res = new MatrixCursor(new String[] {"_id", "ari", "bookName", "text"});

		int c = 0;
		for (int i = 0, len = aris.size(); i < len; i+=2) {
			int ari_start = aris.get(i);
			int ari_end = aris.get(i + 1);
			
			if (ari_start == 0 || ari_end == 0) {
				continue;
			}
			
			if (ari_start == ari_end) {
				// case: single verse
				int ari = ari_start;
				Book book = S.activeVersion.getBook(Ari.toBook(ari));
				if (book != null) {
					String text = S.loadVerseText(S.activeVersion, ari);
					if (formatting == false) {
						text = U.removeSpecialCodes(text);
					}
					res.addRow(new Object[] {++c, ari, book.judul, text});
				}
			} else {
				int ari_start_bc = Ari.toBookChapter(ari_start);
				int ari_end_bc = Ari.toBookChapter(ari_end);
				
				if (ari_start_bc == ari_end_bc) {
					// case: multiple verses in the same chapter
					Book book = S.activeVersion.getBook(Ari.toBook(ari_start));
					if (book != null) {
						c += resultForOneChapter(res, book, c, ari_start_bc, Ari.toVerse(ari_start), Ari.toVerse(ari_end), formatting);
					}
				} else {
					// case: multiple verses in different chapters
					for (int ari_bc = ari_start_bc; ari_bc <= ari_end_bc; ari_bc += 0x0100) {
						Book book = S.activeVersion.getBook(Ari.toBook(ari_bc));
						int chapter_1 = Ari.toChapter(ari_bc);
						if (book == null || chapter_1 <= 0 || chapter_1 > book.nchapter) {
							continue;
						}
						
						if (ari_bc == ari_start_bc) { // we're at the first requested chapter
							c += resultForOneChapter(res, book, c, ari_bc, Ari.toVerse(ari_start), 0xff, formatting); 
						} else if (ari_bc == ari_end_bc) { // we're at the last requested chapter
							c += resultForOneChapter(res, book, c, ari_bc, 0x01, Ari.toVerse(ari_end), formatting);
						} else { // we're at the middle, request all verses!
							c += resultForOneChapter(res, book, c, ari_bc, 0x01, 0xff, formatting);
						}
					}
				}
			}
		}
		
		return res;
	}
	
	/**
	 * @param book 
	 * @return number of verses put into the cursor
	 */
	private int resultForOneChapter(MatrixCursor cursor, Book book, int last_c, int ari_bc, int v_1_start, int v_1_end, boolean formatting) {
		int count = 0;
		String[] chapterText = S.loadChapterText(S.activeVersion, book, Ari.toChapter(ari_bc));
		for (int v_1 = v_1_start; v_1 <= v_1_end; v_1++) {
			int v_0 = v_1 - 1;
			if (v_0 < chapterText.length) {
				int ari = ari_bc | v_1;
				String text = chapterText[v_0];
				if (formatting == false) {
					text = U.removeSpecialCodes(text);
				}
				count++;
				cursor.addRow(new Object[] {last_c + count, ari, book.judul, text});
			} else {
				// we're done with this chapter, no need to loop again
				break;
			}
		}
		return count;
	}
	
	private Cursor getCursorForBibleVersions() {
		MatrixCursor res = new MatrixCursor(new String[] {"_id", "type", "available", "shortName", "longName", "description"});

		long _id = 0;
		{ // internal
			AppConfig c = AppConfig.get(getContext());
			res.addRow(new Object[] {++_id, "internal", 1, c.internalShortName, c.internalLongName, c.internalLongName});
		}
		{ // presets
			List<MVersionPreset> presets = AppConfig.get(App.context).presets;
			for (MVersionPreset preset: presets) {
				res.addRow(new Object[] {++_id, "preset", preset.hasDataFile()? 1: 0, preset.shortName != null? preset.shortName: preset.longName, preset.longName, preset.longName});
			}
		}
		{ // yes
			List<MVersionYes> yeses = S.getDb().listAllVersions();
			for (MVersionYes yes: yeses) {
				res.addRow(new Object[] {++_id, "yes", yes.hasDataFile()? 1:0, yes.shortName != null? yes.shortName: yes.longName, yes.longName, yes.description});
			}
		}
		
		return res;
	}
	
	/** Similar to Integer.parseInt() but supports 0x and won't throw any exception when failed */
	private static int parseInt(String s, int def) {
		if (s == null || s.length() == 0) return def;
		
		// need to trim?
		if (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ') {
			s = s.trim();
		}
		
		// 0x?
		if (s.startsWith("0x")) {
			try {
				return Integer.parseInt(s.substring(2), 16);
			} catch (NumberFormatException e) {
				return def;
			}
		}
		
		// normal decimal
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}
	
	private static boolean parseBoolean(String s) {
		if (s == null) return false;
		if (s.equals("0")) return false;
		if (s.equals("1")) return true;
		if (s.equals("false")) return false;
		if (s.equals("true")) return true;
		s = s.toLowerCase(Locale.US);
		if (s.equals("false")) return false;
		if (s.equals("true")) return true;
		if (s.equals("no")) return false;
		if (s.equals("yes")) return true;
		int n = parseInt(s, Integer.MIN_VALUE);
		if (n == 0) return false;
		if (n != Integer.MIN_VALUE) return true;
		return false;
	}

	@Override public String getType(Uri uri) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override public int delete(Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}