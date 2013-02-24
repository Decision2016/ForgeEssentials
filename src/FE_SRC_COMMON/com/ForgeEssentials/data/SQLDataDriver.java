package com.ForgeEssentials.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import net.minecraftforge.common.Configuration;

import com.ForgeEssentials.api.data.ClassContainer;
import com.ForgeEssentials.api.data.DataStorageManager;
import com.ForgeEssentials.api.data.EnumDriverType;
import com.ForgeEssentials.api.data.ITypeInfo;
import com.ForgeEssentials.api.data.TypeData;
import com.ForgeEssentials.core.ForgeEssentials;
import com.ForgeEssentials.util.DBConnector;
import com.ForgeEssentials.util.EnumDBType;
import com.ForgeEssentials.util.OutputHandler;
import com.ForgeEssentials.util.Pair;

public class SQLDataDriver extends AbstractDataDriver
{
	protected static final String		SEPERATOR			= "__";
	private Connection					dbConnection;
	protected HashMap<Class, Boolean>	classTableChecked	= new HashMap<Class, Boolean>();
	protected DBConnector				connector;

	private final String				UNIQUE				= "uniqueIdentifier";

	// Default constructor is good enough for us.

	public SQLDataDriver()
	{
		connector = new DBConnector("CoreData", null, EnumDBType.H2_FILE, "ForgeEssentials", ForgeEssentials.FEDIR.getPath() + "/FEData", false);
	}

	@Override
	public void loadFromConfigs(Configuration config, String category, String worldName) throws SQLException, ClassNotFoundException
	{
		String cat = category.substring(0, category.lastIndexOf('.'));

		connector.loadOrGenerate(config, cat);
		dbConnection = connector.getChosenConnection();
	}

	@Override
	public void onClassRegistered(ITypeInfo tagger)
	{
		// If this is the first time registering a class that is NOT saved
		// inline,
		// attempt to create a table.
		if (!(tagger.canSaveInline() || classTableChecked.containsKey(tagger.getType())))
		{
			createTable(tagger.getType());
		}
	}

	@Override
	protected boolean saveData(ClassContainer type, TypeData data)
	{
		boolean isSuccess = false;

		try
		{
			Statement s;
			s = dbConnection.createStatement();
			int count = s.executeUpdate(createInsertStatement(type, data));

			isSuccess = true;
		}
		catch (SQLException e)
		{
			OutputHandler.info("Couldn't save object of type " + type.getSimpleName() + " to " + connector.getChosenType() + " DB. Server will continue running.");
			e.printStackTrace();
		}

		return isSuccess;
	}

	@Override
	protected TypeData loadData(ClassContainer type, String uniqueKey)
	{
		TypeData reconstructed = DataStorageManager.getDataForType(type);
		ITypeInfo info = DataStorageManager.getInfoForType(type);

		try
		{
			Statement s = dbConnection.createStatement();
			ResultSet result = s.executeQuery(createSelectStatement(type, uniqueKey));

			// ResultSet initially sits just before first result.
			if (result.next())
			{
				// Should only be one item in this set.
				createTaggedClassFromResult(resultRowToMap(result), info, reconstructed);
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}

		return reconstructed;
	}

	@Override
	protected TypeData[] loadAll(ClassContainer type)
	{
		ArrayList<TypeData> values = new ArrayList<TypeData>();
		ITypeInfo info = DataStorageManager.getInfoForType(type);

		try
		{
			Statement s = dbConnection.createStatement();
			ResultSet result = s.executeQuery(createSelectAllStatement(type));
			TypeData temp;

			while (result.next())
			{
				temp = DataStorageManager.getDataForType(type);

				// Continue reading rows as they exist.
				createTaggedClassFromResult(resultRowToMap(result), info, temp);
				values.add(temp);
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}

		return values.toArray(new TypeData[values.size()]);
	}

	@Override
	protected boolean deleteData(ClassContainer type, String uniqueObjectKey)
	{
		boolean isSuccess = false;

		try
		{
			Statement s = dbConnection.createStatement();
			s.execute(createDeleteStatement(type, uniqueObjectKey));

			isSuccess = true;
		}
		catch (SQLException e)
		{
			OutputHandler.severe("Problem deleting data from " + connector.getChosenType() + " DB (May not actually be a critical error):");
			e.printStackTrace();
		}

		return isSuccess;
	}

	// Transforms a ResultSet row into a HashMap. Assumes a valid result is
	// currently selected.
	private HashMap<String, Object> resultRowToMap(ResultSet result)
	{
		HashMap<String, Object> map = new HashMap();

		try
		{
			// Determine column names
			ResultSetMetaData meta = result.getMetaData();
			ArrayList<String> names = new ArrayList();

			// ResultSet columns start at 1. (Crazy, right?)
			for (int i = 1; i < meta.getColumnCount(); ++i)
			{
				names.add(meta.getColumnName(i));
			}

			// Pull values into map.
			Object val = null;
			for (String name : names)
			{
				val = result.getObject(name);
				if (val != null)
				{
					// Only add something to the map if it has a value.
					map.put(name, val);
				}
			}

		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}

		return map;
	}

	private void createTaggedClassFromResult(HashMap<String, Object> result, ITypeInfo info, TypeData data)
	{
		ITypeInfo taggerCursor;
		TypeData cursor = null;
		SavedField tmpField = null;
		ClassContainer tmpClass;

		Object tempVal;

		for (Entry<String, Object> entry : result.entrySet())
		{
			cursor = data;
			taggerCursor = info;

			String[] fieldHeiarchy = entry.getKey().split(SEPERATOR);
			if (fieldHeiarchy != null)
			{
				// Iterate over the list of items in the heiarchy to rebuild the
				// TaggedClass.
				for (int i = 0; i < fieldHeiarchy.length; ++i)
				{
					// Grab the next item
					tmpField = getSavedFieldFromName(cursor, fieldHeiarchy[i]);

					if (tmpField == null)
					{
						// Create a new node for this position.
						tmpField = new SavedField();
						tmpField.name = fieldHeiarchy[i];
						cursor.putField(tmpField.name, null);
					}

					if (fieldHeiarchy.length > i + 1)
					{
						// An object lives here.
						tmpClassContainer = taggerCursor.getTypeOfField(fieldHeiarchy[i]);
						tmpField.value = cursor = DataStorageManager.getDataForType(tmpClass);
						taggerCursor = taggerCursor.getInfoForField(fieldHeiarchy[i]);
					}
					else
					{
						// Primitive type.
						ClassContainer fieldType = taggerCursor.getTypeOfField(fieldHeiarchy[i]);
						tmpField.value = valueToField(fieldType.getType(), result.get(fieldHeiarchy[i]));
						tmpField.type = fieldType.getType();
					}
				}
			}
		}
	}

	/**
	 * if the field exists, but has no value. An empty field is returned.
	 * if the field does not exist, NULL is returned
	 * otherwise a field is constructed and returned.
	 * @param data
	 */
	private SavedField getSavedFieldFromName(TypeData data, String name)
	{
		SavedField field = new SavedField();

		if (!data.hasField(name))
			return null;
		else
		{
			Object val;
			val = data.getFieldValue(name);
			if (val == null)
				return field;
			else
				return new SavedField(name, val);
		}
	}

	private String createDeleteStatement(ClassContainer type, String uniqueObjectKey)
	{
		StringBuilder builder = new StringBuilder();
		builder.append("DELETE FROM " + type.getSimpleName() + " WHERE ");
		ITypeInfo tagger = DataStorageManager.getInfoForType(type);
		builder.append(UNIQUE).append(" = ");
		builder.append(uniqueObjectKey.toString());

		return builder.toString();
	}

	private String createSelectAllStatement(ClassContainer type)
	{
		return createSelectStatement(type, null);
	}

	private String createSelectStatement(ClassContainer type, Object uniqueObjectKey)
	{
		StringBuilder builder = new StringBuilder();

		// Basic SELECT syntax
		builder.append("SELECT * FROM " + type.getSimpleName());
		// Conditional
		if (uniqueObjectKey != null)
		{
			builder.append(" WHERE ");
			ITypeInfo tagger = DataStorageManager.getInfoForType(type);
			builder.append(UNIQUE);
			builder.append(" = ");
			builder.append('\'').append(uniqueObjectKey).append('\'');
		}

		return builder.toString();
	}

	private String createInsertStatement(ClassContainer type, TypeData fieldList)
	{
		ArrayList<Pair<String, String>> fieldValueMap = new ArrayList<Pair<String, String>>();
		// Iterate through fields and build up name=>value pair list.
		for (Entry<String, Object> entry : fieldList.getAllFields())
		{
			fieldValueMap.addAll(fieldToValues(entry.getKey(), entry.getValue() == null ? null : entry.getValue().getClass(), entry.getValue()));
		}

		// Build up update statement.
		StringBuilder query = new StringBuilder();
		query.append("INSERT OR REPLACE INTO " + type.getSimpleName() + ' ');

		StringBuilder fields = new StringBuilder();
		StringBuilder values = new StringBuilder();
		fields.append('(');
		values.append('(');

		// Deal with unique field
		ITypeInfo tagger = DataStorageManager.getInfoForType(type);
		fields.append(UNIQUE);
		values.append('\'').append(fieldList.getUniqueKey()).append('\'');

		Iterator<Pair<String, String>> itr = fieldValueMap.iterator();
		Pair<String, String> pair;
		while (itr.hasNext())
		{
			pair = itr.next();
			fields.append(", " + pair.getFirst());
			values.append(", ");
			if (pair.getSecond().getClass().equals(String.class))
			{
				values.append("'" + pair.getSecond() + "'");
			}
			else
			{
				values.append(pair.getSecond());
			}
		}
		fields.append(')');
		values.append(')');

		query.append(fields.toString() + " VALUES " + values.toString());

		return query.toString();
	}

	/**
	 * Attempts to create a table to store the type passed to it. These should
	 * only be top-level types that need to be stored, such as PlayerInfo and
	 * Zones. Points, WorldPoints and other "simple" types that are contained
	 * within the top-level types will be unrolled automatically.
	 * @param type
	 * @return
	 */
	private boolean createTable(ClassContainer type)
	{
		boolean isSuccess = false;

		ITypeInfo tagger = DataStorageManager.getInfoForType(type);
		String[] fields = tagger.getFieldList();
		ArrayList<Pair<String, String>> tableFields = new ArrayList<Pair<String, String>>();
		String keyClause = null;

		for (String name : fields)
		{
			tableFields.addAll(fieldToColumns(name, tagger.getTypeOfField(name)));
		}

		// Is a method. Extra field required.
		tableFields.add(new Pair<String, String>(UNIQUE, "VARCHAR(100)"));
		keyClause = "PRIMARY KEY (" + UNIQUE + ")";

		// Build up the create statement
		StringBuilder tableCreate = new StringBuilder("CREATE TABLE IF NOT EXISTS " + type.getSimpleName() + " (");
		for (Pair<String, String> pair : tableFields)
		{
			tableCreate.append(pair.getFirst() + " " + pair.getSecond() + ", ");
		}
		// Add primary key clause.
		tableCreate.append(keyClause + ")");

		try
		{
			// Attempt to execute the statement.
			Statement s = dbConnection.createStatement();
			s.execute(tableCreate.toString());

			isSuccess = true;
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		return isSuccess;
	}

	/**
	 * Examines the provided type and produces an array of field => H2 Type
	 * pairs, ideal for creating new tables with. Complex type fields are broken
	 * down into constituent primitives in the form of:
	 * "parentField_childFieldName"
	 * @param fieldName
	 *            Name of saved field
	 * @param type
	 *            Type of saved field
	 * @return Array of field => H2 type names.
	 */
	private ArrayList<Pair<String, String>> fieldToColumns(String fieldName, ClassContainer type)
	{
		ArrayList<Pair<String, String>> fields = new ArrayList<Pair<String, String>>();

		if (!StorageManager.isTypeComplex(type))
		{
			if (type.equals(int.class) || type.equals(Integer.class) || type.equals(boolean.class) || type.equals(Boolean.class))
			{
				fields.add(new Pair<String, String>(fieldName, "INT"));
			}
			else if (type.equals(float.class) || type.equals(Float.class) || type.equals(double.class) || type.equals(Double.class))
			{
				fields.add(new Pair<String, String>(fieldName, "DOUBLE"));
			}
			else if (type.equals(String.class))
			{
				fields.add(new Pair<String, String>(fieldName, "VARCHAR(255)"));
			}
			else if (type.equals(double[].class) || type.equals(int[].class) || type.equals(boolean[].class) || type.equals(String[].class))
			{
				// We are going to roll arrays up into arbitrary long text
				// fields.
				fields.add(new Pair<String, String>(fieldName, "TEXT"));
			}
			else
			{
				// Unsupported. This will probably be crazy.
				fields.add(new Pair<String, String>(fieldName, "BLOB"));
			}
		}
		else
		{
			// Complex type we can't handle.
			ITypeInfo tagger = DataStorageManager.getInfoForType(type);
			String[] fieldList = tagger.getFieldList();

			// Iterate over the stored fields. Recurse if nessecary.
			for (String name : fieldList)
			{
				fields.addAll(fieldToColumns(fieldName + SEPERATOR + name, tagger.getTypeOfField(name)));
			}
		}

		return fields;
	}

	/**
	 * Generates an array of fieldname => String(Value) pairs, useful for
	 * Inserts, Updates, or Deletes.
	 * @param fieldName Name of the field in the H2 DB
	 * @param type Type of field (Java)
	 * @param value
	 * @return Array of fieldname => value pairs
	 */
	private ArrayList<Pair<String, String>> fieldToValues(String fieldName, ClassContainer type, Object value)
	{
		ArrayList<Pair<String, String>> data = new ArrayList<Pair<String, String>>();

		if (type.equals(Integer.class) || type.equals(Boolean.class) || type.equals(Float.class) || type.equals(Double.class) || type.equals(String.class))
		{
			data.add(new Pair(fieldName, value.toString()));
		}
		else if (type.equals(double[].class) && ((double[]) value).length > 0)
		{
			double[] arr = (double[]) value;
			StringBuilder tempStr = new StringBuilder();
			tempStr.append("'").append(String.valueOf(arr[0]));
			for (int i = 1; i < arr.length; ++i)
			{
				tempStr.append("," + String.valueOf(arr[i]));
			}
			data.add(new Pair(fieldName, tempStr.append("'").toString()));
		}
		else if (type.equals(int[].class) && ((int[]) value).length > 0)
		{
			int[] arr = (int[]) value;
			StringBuilder tempStr = new StringBuilder();
			tempStr.append("'").append(String.valueOf(arr[0]));
			for (int i = 1; i < arr.length; ++i)
			{
				tempStr.append("," + String.valueOf(arr[i]));
			}
			data.add(new Pair(fieldName, tempStr.append("'").toString()));
		}
		else if (type.equals(boolean[].class) && ((boolean[]) value).length > 0)
		{
			boolean[] arr = (boolean[]) value;
			StringBuilder tempStr = new StringBuilder();
			tempStr.append("'").append(String.valueOf(arr[0]));
			for (int i = 1; i < arr.length; ++i)
			{
				tempStr.append("," + String.valueOf(arr[i]));
			}
			data.add(new Pair(fieldName, tempStr.append("'").toString()));
		}
		else if (type.equals(String[].class) && ((String[]) value).length > 0)
		{
			String[] arr = (String[]) value;
			StringBuilder tempStr = new StringBuilder();
			tempStr.append("'").append(String.valueOf(arr[0]).replace("'", "\"\""));
			for (int i = 1; i < arr.length; ++i)
			{
				tempStr.append("!??!" + String.valueOf(arr[i]).replace("'", "\"\""));
			}
			data.add(new Pair(fieldName, tempStr.append("'").toString()));
		}
		else if (type.equals(TypeData.class))
		{
			// Tricky business involving recursion.
			TypeData tc = (TypeData) value;

			for (Entry<String, Object> f : tc.getAllFields())
			{
				data.addAll(fieldToValues(fieldName + SEPERATOR + f.getKey(), f.getKey() == null ? null : f.getValue().getClass(), f.getValue()));
			}
		}
		else
		// What the fuck? This will be unpredictable.
		{
			data.add(new Pair(fieldName, value.toString()));
		}
		return data;
	}

	// Transforms the raw DB type back into a Java object.
	private Object valueToField(ClassContainer targetType, Object dbValue)
	{
		Object value = null;
		if (targetType.equals(int.class))
		{
			// DB Value is an integer
			value = dbValue;
		}
		else if (targetType.equals(double.class))
		{
			// DB Value is a double
			value = dbValue;
		}
		else if (targetType.equals(float.class))
		{
			// DB value is a Double.
			value = ((Double) dbValue).floatValue();
		}
		else if (targetType.equals(String.class))
		{
			// DB Value is a string
			value = dbValue;
		}
		else if (targetType.equals(boolean.class))
		{
			// DB Value is an integer (1=true, 0=false)
			value = ((Integer) dbValue).equals(1);
		}
		else if (targetType.equals(double[].class))
		{
			// DB value is a string representing an array of doubles, separated
			// by ','
			String[] values = ((String) dbValue).split(",");
			double[] result = new double[values.length];

			for (int i = 0; i < values.length; ++i)
			{
				result[i] = Double.valueOf(values[i]).doubleValue();
			}
			value = result;
		}
		else if (targetType.equals(int[].class))
		{
			// DB value is a string representing an array of integers, separated
			// by ','
			String[] values = ((String) dbValue).split(",");
			int[] result = new int[values.length];

			for (int i = 0; i < values.length; ++i)
			{
				result[i] = Integer.valueOf(values[i]).intValue();
			}
			value = result;
		}
		else if (targetType.equals(boolean[].class))
		{
			// DB value is a string representing an array of booleans, separated
			// by ','
			String[] values = ((String) dbValue).split(",");
			boolean[] result = new boolean[values.length];

			for (int i = 0; i < values.length; ++i)
			{
				result[i] = Boolean.valueOf(values[i]).booleanValue();
			}
			value = result;
		}
		else if (targetType.equals(String[].class))
		{
			// DB value is a string representing an array of strings, separated
			// by '!??!'
			// Each item may contain instances of '""', which represents a
			// single apostrophe.
			String[] values = ((String) dbValue).split("!??!");

			for (int i = 0; i < values.length; ++i)
			{
				values[i] = values[i].replaceAll("\"\"", "'");
			}
			value = values;
		}
		return value;
	}

	@Override
	public EnumDriverType getType()
	{
		return EnumDriverType.SQL;
	}
}
