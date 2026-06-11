package com.wenz.radiomod.block;

import com.wenz.radiomod.RadioMod;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockRadio extends Block implements ITileEntityProvider {

    public static final PropertyDirection FACING = PropertyDirection.create("facing", EnumFacing.Plane.HORIZONTAL);

    private static final AxisAlignedBB SHAPE = new AxisAlignedBB(
            2.0 / 16.0, 0.0, 3.0 / 16.0,
            14.0 / 16.0, 10.0 / 16.0, 13.0 / 16.0);

    public BlockRadio() {
        super(Material.WOOD);
        setRegistryName(RadioMod.MODID, "radio");
        setUnlocalizedName(RadioMod.MODID + ".radio");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(1.5f);
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    /* ---------- shape ---------- */

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return SHAPE;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) { return false; }

    @Override
    public boolean isFullCube(IBlockState state) { return false; }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    /* ---------- block state ---------- */

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ,
                                            int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta));
    }

    /* ---------- tile entity ---------- */

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileRadio();
    }

    /* ---------- interaction ---------- */

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand,
                                    EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote && player instanceof EntityPlayerMP) {
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileRadio) {
                TileRadio radio = (TileRadio) te;

                // Check if another player has the GUI open
                if (radio.isLocked() && !radio.isLockedBy(player)) {
                    player.sendMessage(new TextComponentString(
                            "\u00a7c\u0420\u0430\u0434\u0438\u043e \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0435\u0442\u0441\u044f \u0438\u0433\u0440\u043e\u043a\u043e\u043c " + radio.getLockedByName()));
                    return true;
                }

                // Lock and open GUI
                radio.lock(player);
                player.openGui(RadioMod.instance, 0, world, pos.getX(), pos.getY(), pos.getZ());
            }
        }
        return true;
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileRadio) {
            ((TileRadio) te).forceUnlock();
        }
        super.breakBlock(world, pos, state);
    }
}
