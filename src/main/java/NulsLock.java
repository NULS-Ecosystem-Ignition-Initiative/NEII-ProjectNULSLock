import ch.qos.logback.core.util.COWArrayList;
import com.fasterxml.jackson.databind.BeanProperty;
import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.*;
import io.nuls.contract.sdk.event.DebugEvent;
import org.checkerframework.checker.units.qual.A;

import javax.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reentrancyguard.ReentrancyGuard;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;


/**
* @notice Nuls Contract that locks the nuls deposited, returns
* yield to a project during an x period of time and
* returns nuls locked in the end of the period
*
* @dev Nuls are deposited in AINULS in order to receive yield
*
* Developed by Pedro G. S. Ferreira @Pedro_Ferreir_a
* */
public class NulsLock extends ReentrancyGuard implements Contract{

    /** 100 NULS
     *   @dev Min required to deposit in aiNULS is 100 NULS
     */
    private static final BigInteger ONE_HUNDREAD_NULS = BigInteger.valueOf(10000000000L);
    private BigInteger LOCK_TIME                      = BigInteger.valueOf(2 * 365 * 24 * 60 * 60); // not static to be chhanged by admin

    //Contract where NULS are deposited and AINULS Token Contract
    public Address aiNULSDepositContract;
    public Address aiNULS;

    public Boolean paused;

    public BigInteger inWaitingRooM;


    //User Balance
    public Map<Address, BigInteger> userBalance  = new HashMap<>();
    public Map<Address, BigInteger> userLockTime = new HashMap<>();
    public Map<Address, Boolean>    projectAdmin = new HashMap<>();

    //--------------------------------------------------------------------
    //Initialize Contract
    public NulsLock(@Required Address aiNULSDepositContract_, @Required Address aiNULS_, @Required Address admin_) {

        aiNULSDepositContract_  = aiNULSDepositContract_;
        aiNULS                  = aiNULS_;
        inWaitingRooM           = BigInteger.ZERO;
        projectAdmin.put(admin_, true);
        paused = false;


    }

    /** VIEW FUNCTIONS */

    /**
     * @notice Get aiNULS Deposit Contract address
     *
     * @dev    Contract where nuls will be locked
     *         and earn yield
     * @return aiNULS Deposit Contract Address
     */
    @View
    public Address getAINULSDepositCtrAddr() {
        return aiNULSDepositContract;
    }

    /**
     * @notice Get aiNULS asset address
     *
     * @return aiNULS Token Contract Address
     */
    @View
    public Address getAINULSCtrAddr() {
        return aiNULS;
    }

    /**
     * @notice Verify if Address is admin
     *
     * @return true if it is admin, false if not
     */
    @View
    public Boolean isAdmin(Address admin) {
        if(projectAdmin.get(admin) == null)
            return false;
        return projectAdmin.get(admin);
    }

    /**
     * @notice Get user balance deposited in lock
     *
     * @return User Balance
     */
    @View
    public BigInteger getUserBalance(Address addr){
        if(userBalance.get(addr) == null)
            return BigInteger.ZERO;
        return userBalance.get(addr);
    }

    /**
     * @notice Get user lock time ending
     *
     * @return User lock time ending
     */
    @View
    public BigInteger getUserLockTime(Address addr){
        if(userLockTime.get(addr) == null)
            return BigInteger.ZERO;
        return userLockTime.get(addr);
    }

    @View
    public Boolean isPaused(){
        return paused;
    }

    /** MODIFIER FUNCTIONS */

    public void onlyAdmin(){
        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");
    }

    public void notPaused(){
        require(!paused, "");
    }

    /** MUTABLE NON-OWNER FUNCTIONS */

    /**
     * Deposit funds on Lock
     *
     * */
    @Payable
    public void lockDeposit(@Required Address onBehalfOf, BigInteger amount) {

        //Prevent Reentrancy Attacks
        setEntrance();

        //Only allow locks when not paused
        notPaused();

        //Require that nuls sent match the amount to lock
        require(Msg.value().compareTo(amount)  >= 0, "Invalid Amount sent");

        if(userBalance.get(onBehalfOf) == null){

            userBalance.put(onBehalfOf, amount);
            extendUserLock(onBehalfOf, LOCK_TIME);

        }else{

            userBalance.put(onBehalfOf, userBalance.get(onBehalfOf).add(amount));
            extendUserLock(onBehalfOf, LOCK_TIME);

        }

        BigInteger ctrBal = Msg.address().totalBalance();

        if(ctrBal.compareTo(ONE_HUNDREAD_NULS) >= 0){
            stakeInAINULS(ctrBal);
            inWaitingRooM = BigInteger.ZERO;
        }else{
            inWaitingRooM = ctrBal;
        }

        setClosure();

    }

    public void claimRewards(Address receiver){

        setEntrance();

        notPaused();

        onlyAdmin();

        // Staked amoun in ainuls
        BigInteger stakedInAiNULS = getBalAINULS(Msg.address());

        withdrawInAINULS();

        // Stake everything again to gain more rewards for future claims
        stakeInAINULS(stakedInAiNULS);

        BigInteger balNow = Msg.address().totalBalance();

        if(balNow.subtract(inWaitingRooM).compareTo(BigInteger.ZERO) > 0){
            receiver.transfer(balNow.subtract(inWaitingRooM));
        }

        setClosure();

    }

    public void withdrawAfterLock(){

        setEntrance();

        notPaused();

        require(userLockTime.get(Msg.sender())!= null
                && userLockTime.get(Msg.sender()).compareTo(BigInteger.valueOf(Block.timestamp())) <= 0,
                "Lock still active"
        );

        //Require that user has funds to withdraw
        if(userBalance.get(Msg.sender()) != null && userBalance.get(Msg.sender()).compareTo(BigInteger.ZERO) > 0){

            BigInteger balToWithdraw = userBalance.get(Msg.sender());

            BigInteger stakedInAiNULS = getBalAINULS(Msg.address());

            if((stakedInAiNULS.subtract(balToWithdraw)).compareTo(BigInteger.ZERO) >= 0) {

                withdrawInAINULS();

                stakeInAINULS(stakedInAiNULS.subtract(balToWithdraw));

                userBalance.put(Msg.sender(), BigInteger.ZERO);

                Msg.sender().transfer(balToWithdraw);
            }

        }else{
            require(false, "No Amount Deposited");
        }

        setClosure();
    }

    //--------------------------------------------------------------------
    /** MUTABLE OWNER FUNCTIONS */

    public void setAiNULSDepositContract(Address newDepositCtr){
        onlyAdmin();
        aiNULSDepositContract = newDepositCtr;
    }

    public void setAiNULS(Address newAiNULS){
        onlyAdmin();
        aiNULS = newAiNULS;
    }

    public void addAdmin(Address newAdmin){

        onlyAdmin();

        projectAdmin.put(newAdmin, true);

    }

    public void removeAdmin(Address removeAdmin){

        onlyAdmin();
        require(!Msg.sender().equals(removeAdmin), "Can't remove itself");

        projectAdmin.put(removeAdmin, false);

    }

    public void setLOCK_TIME(@Required BigInteger newLockTime){
        onlyAdmin();
        LOCK_TIME = newLockTime;
    }

    public void setPaused(){
        onlyAdmin();
        require(!paused, "Already Paused");
        paused = true;
    }

    public void setUnpaused(){
        onlyAdmin();
        require(paused, "Already Unpaused");
        paused = false;
    }

    public void migrateToNewContract(Address recipient){
        onlyAdmin();
        require(recipient.isContract(), "Only allow migration to new contract");
        safeTransfer(aiNULS, recipient, getBalAINULS(Msg.address()));
    }

    public void migrateToNewContractNuls(Address recipient){
        onlyAdmin();
        require(recipient.isContract(), "Only allow migration to new contract");
        recipient.transfer(Msg.address().totalBalance());
    }

    public void setUserLock(@Required Address onBehalfOf, BigInteger timeInSeconds){
        onlyAdmin();
        userLockTime.put(onBehalfOf, timeInSeconds);
    }

    /** Essential to receive funds back from aiNULS
     *
     * @dev DON'T REMOVE IT,
     *      if you do you will be unable to withdraw from aiNULS
     */
    @Payable
    public void _payable() {

    }

    //--------------------------------------------------------------------
    /** INTERNAL FUNCTIONS */


    private void extendUserLock(Address onBehalfOf, BigInteger timeInSeconds){
        userLockTime.put(onBehalfOf, BigInteger.valueOf(Block.timestamp()).add(timeInSeconds));
    }

    private void stakeInAINULS(@Required BigInteger amount){
        String[][] args = new String[][]{new String[]{amount.toString()}};
        aiNULSDepositContract.callWithReturnValue("stake", "", args, amount);
    }

    private void withdrawInAINULS(){
        aiNULSDepositContract.callWithReturnValue("withdraw", "", null, BigInteger.ZERO);
    }

    private BigInteger getBalAINULS(@Required Address owner){
        String[][] args = new String[][]{new String[]{owner.toString()}};
        BigInteger b = new BigInteger(aiNULS.callWithReturnValue("balanceOf", "", args, BigInteger.ZERO));
        return b;
    }

    private void safeTransfer(@Required Address token, @Required Address recipient, @Required BigInteger amount){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NulswapLendingV1: Failed to transfer");
    }

}