package com.ForgeEssentials.data;

import java.io.File;
import java.util.ArrayList;

import net.minecraftforge.common.Configuration;
import net.minecraftforge.common.Property;

import com.ForgeEssentials.api.data.ClassContainer;
import com.ForgeEssentials.api.data.EnumDriverType;
import com.ForgeEssentials.api.data.IReconstructData;
import com.ForgeEssentials.api.data.TypeData;
import com.ForgeEssentials.core.ForgeEssentials;
import com.ForgeEssentials.util.FunctionHelper;

import cpw.mods.fml.common.FMLCommonHandler;

public abstract class BinaryDataDriver extends AbstractDataDriver
{
	protected File		baseFile;
	protected static final String	EXT = ".dat";

	@Override
	public final void loadFromConfigs(Configuration config, String category, String worldName) throws Exception
	{
		Property prop;

		String cat = category.substring(0, category.lastIndexOf('.'));

		prop = config.get(cat, "useFEDataDir", false);
		prop.comment = "Set to true to use the '.minecraft/ForgeEssentials/saves' directory instead of a world. Server owners may wish to set this to true.";

		boolean useFEDir = prop.getBoolean(false);

		if (useFEDir)
		{
			baseFile = new File(ForgeEssentials.FEDIR, "saves/" + getName() + "/" + worldName + "/");
		}
		else
		{
			File parent = FunctionHelper.getBaseDir();
			if (FMLCommonHandler.instance().getSide().isClient())
			{
				parent = new File(FunctionHelper.getBaseDir(), "saves/");
			}

			baseFile = new File(parent, worldName + "/FEData/" + getName() + "/");
		}

		config.save();
	}

	protected final File getTypePath(ClassContainer type)
	{
		return new File(baseFile, type.getFileSafeName() + "/");
	}

	/**
	 * always uses the .dat extension
	 * 
	 * @return
	 */
	protected File getFilePath(ClassContainer type, Object uniqueKey)
	{
		return new File(getTypePath(type).getPath(), uniqueKey.toString() + EXT);
	}

	@Override
	protected TypeData[] loadAll(ClassContainer type)
	{
		File[] files = getTypePath(type).listFiles();
		ArrayList<IReconstructData> data = new ArrayList<IReconstructData>();

		if (files != null)
		{
			for (File file : files)
			{
				if (!file.isDirectory() && file.getName().endsWith(EXT))
				{
					data.add(loadData(type, file.getName().replace(EXT, "")));
				}
			}
		}

		return data.toArray(new TypeData[] {});
	}

	@Override
	protected final boolean deleteData(ClassContainer type, String uniqueKey)
	{
		boolean isSuccess = false;
		File f = getFilePath(type, uniqueKey);

		if (f.exists())
		{
			isSuccess = true;
			f.delete();
		}

		return isSuccess;
	}

	@Override
	public final EnumDriverType getType()
	{
		return EnumDriverType.BINARY;
	}

}
