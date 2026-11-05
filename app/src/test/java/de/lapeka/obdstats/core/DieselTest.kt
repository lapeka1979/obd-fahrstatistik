package de.lapeka.obdstats.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DieselTest {
    @Test fun lambdaFromWidebandSensor() {
        // 0x8000 = Lambda 1,0; 0xC000 = 1,5
        assertEquals(1.0, Pid.LAMBDA_O2_B.parse("41348000FFFF")!!, 0.001)
        assertEquals(1.5, Pid.LAMBDA_CMD.parse("4144C000")!!, 0.001)
    }

    @Test fun torquePercentAndReference() {
        assertEquals(25.0, Pid.TORQUE_ACTUAL.parse("416296")!!, 0.001)
        assertEquals(400.0, Pid.TORQUE_REF.parse("41630190")!!, 0.001)
    }

    @Test fun dieselMafWithLambdaIsFarBelowStoichiometric() {
        val stoich = FuelCalc.litersPerHourFromMaf(30.0, FuelType.DIESEL)
        val real = FuelCalc.litersPerHourFromMafLambda(30.0, 2.0, FuelType.DIESEL)
        assertEquals(stoich / 2, real, 0.001)
    }

    @Test fun torqueEstimateAt100KmhIsPlausible() {
        // Arteon 2.0 TDI bei ca. 100 km/h: ~20 kW, 2000 U/min → um 5 l/h
        val torqueNm = 20_000.0 / (2000 * 2 * Math.PI / 60)
        val percent = torqueNm / 400.0 * 100
        val lph = FuelCalc.litersPerHourFromTorque(percent, 400.0, 2000.0, 2.0, FuelType.DIESEL)
        assertEquals(5.2, lph, 0.3)
    }

    @Test fun torqueOverrunAndIdle() {
        assertEquals(0.0, FuelCalc.litersPerHourFromTorque(-5.0, 400.0, 1800.0, 2.0, FuelType.DIESEL), 0.0001)
        assertEquals(0.6, FuelCalc.litersPerHourFromTorque(0.0, 400.0, 800.0, 2.0, FuelType.DIESEL), 0.0001)
    }

    @Test fun sourcePriorityForDiesel() {
        val maf = Pid.MAF.code
        val rpm = Pid.RPM.code
        val torque = setOf(Pid.TORQUE_ACTUAL.code, Pid.TORQUE_REF.code, rpm)
        assertEquals(FuelSource.FUEL_RATE, FuelCalc.chooseSource(FuelType.DIESEL, setOf(Pid.FUEL_RATE.code, maf)))
        assertEquals(FuelSource.MAF_LAMBDA, FuelCalc.chooseSource(FuelType.DIESEL, setOf(maf, Pid.LAMBDA_O2_B.code) + torque))
        assertEquals(FuelSource.TORQUE, FuelCalc.chooseSource(FuelType.DIESEL, setOf(maf) + torque))
        assertEquals(FuelSource.MAF, FuelCalc.chooseSource(FuelType.BENZIN, setOf(maf) + torque))
        assertEquals(FuelSource.MAF, FuelCalc.chooseSource(FuelType.DIESEL, setOf(maf, rpm)))
    }
}
